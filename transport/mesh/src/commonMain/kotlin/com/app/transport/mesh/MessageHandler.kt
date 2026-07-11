@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)

package com.app.transport.mesh

import com.app.common.AppDispatchers
import com.app.common.utils.Log
import com.app.transport.features.file.IncomingFileStore
import com.app.transport.model.BitchatFilePacket
import com.app.transport.model.BitchatMessage
import com.app.transport.model.CourierEnvelope
import com.app.transport.model.IdentityAnnouncement
import com.app.transport.model.PeerCapabilities
import com.app.transport.model.NoisePayload
import com.app.transport.model.NoisePayloadType
import com.app.transport.model.PrivateMessagePacket
import com.app.transport.model.RoutedPacket
import com.app.transport.model.messageTypeForMime
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.peerIdToRoutingBytes
import com.app.transport.protocol.MessageType
import com.app.transport.sync.PacketIdUtil
import com.app.common.encoding.toHexString
import com.app.transport.MeshConstants
import com.app.transport.FavoriteNostrLink
import com.app.transport.meshgraph.GossipTLV
import com.app.transport.meshgraph.MeshGraphService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Handles processing of different message types
 * Extracted from BluetoothMeshService for better separation of concerns
 */
internal class MessageHandler(
    private val myPeerID: String,
    private val incomingFileStore: IncomingFileStore,
    private val meshGraphService: MeshGraphService,
    dispatchers: AppDispatchers = AppDispatchers(),
) {
    
    companion object {
        private const val TAG = "MessageHandler"
        // Receiver-side courier dedup window (reference TransportConfig.courierOpenedMessageIDCap).
        private const val OPENED_COURIER_ID_CAP = 512
    }
    
    // Delegate for callbacks
    var delegate: MessageHandlerDelegate? = null
    
    // Reference to PacketProcessor for recursive packet handling
    var packetProcessor: PacketProcessor? = null

    // Noise<->Nostr favorite mapping (injected from BluetoothMeshService).
    var favoriteNostrLink: FavoriteNostrLink? = null
    
    // Coroutines
    private val handlerScope = CoroutineScope(dispatchers.io + SupervisorJob())

    // Receiver-side courier dedup: redundant copies of one message arrive as distinct envelopes
    // (fresh seal per courier, spray forks), so dedup on the inner message ID before delivery/ack.
    // Bounded, insertion-ordered LRU.
    private val openedCourierMessageIDs = LinkedHashSet<String>()

    /**
     * Handle Noise encrypted transport message - SIMPLIFIED iOS-compatible version
     * Uses NoisePayloadType system exactly like iOS SimplifiedBluetoothService
     */
    fun handleNoiseEncrypted(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        
        Log.d(TAG, "Processing Noise encrypted message from $peerID (${packet.payload.size} bytes)")
        
        // Skip our own messages
        if (peerID == myPeerID) return
        
        // Check if this message is for us
        val recipientID = packet.recipientID?.toHexString()
        if (recipientID != myPeerID) {
            Log.d(TAG, "🔐 Encrypted message not for me (for $recipientID, I am $myPeerID)")
            return
        }
        
        try {
            // Decrypt the message using the Noise service
            val decryptedData = delegate?.decryptFromPeer(packet.payload, peerID)
            if (decryptedData == null) {
                Log.w(TAG, "Failed to decrypt Noise message from $peerID - may need handshake")
                return
            }
            
            if (decryptedData.isEmpty()) {
                Log.w(TAG, "Decrypted data is empty from $peerID")
                return
            }
            
            // NEW: Use NoisePayload system exactly like iOS
            val noisePayload = NoisePayload.decode(decryptedData)
            if (noisePayload == null) {
                Log.w(TAG, "Failed to parse NoisePayload from $peerID")
                return
            }
            
            Log.d(TAG, "🔓 Decrypted NoisePayload type ${noisePayload.type} from $peerID")
            
            when (noisePayload.type) {
                NoisePayloadType.PRIVATE_MESSAGE -> {
                    // Decode TLV private message exactly like iOS
                    val privateMessage = PrivateMessagePacket.decode(noisePayload.data)
                    if (privateMessage != null) {
                        Log.d(TAG, "🔓 Decrypted TLV PM from $peerID: ${privateMessage.content.take(30)}...")

                        // Handle favorite/unfavorite notifications embedded as PMs
                        val pmContent = privateMessage.content
                        if (pmContent.startsWith("[FAVORITED]") || pmContent.startsWith("[UNFAVORITED]")) {
                            handleFavoriteNotificationFromMesh(pmContent, peerID)
                            // Acknowledge delivery for UX parity
                            sendDeliveryAck(privateMessage.messageID, peerID)
                            return
                        }
                        
                        // Create BitchatMessage - preserve source packet timestamp
                        val message = BitchatMessage(
                            id = privateMessage.messageID,
                            sender = delegate?.getPeerNickname(peerID) ?: "Unknown",
                            content = privateMessage.content,
                            timestamp = Instant.fromEpochMilliseconds(packet.timestamp.toLong()),
                            isRelay = false,
                            originalSender = null,
                            isPrivate = true,
                            recipientNickname = delegate?.getMyNickname(),
                            senderPeerID = peerID,
                            mentions = null // TODO: Parse mentions if needed
                        )
                        
                        // Notify delegate
                        delegate?.onMessageReceived(message)
                        
                        // Send delivery ACK exactly like iOS
                        sendDeliveryAck(privateMessage.messageID, peerID)
                    }
                }
                
                NoisePayloadType.FILE_TRANSFER -> {
                    // Handle encrypted file transfer; generate unique message ID
                    val file = BitchatFilePacket.decode(noisePayload.data)
                    if (file != null) {
                        Log.d(TAG, "🔓 Decrypted encrypted file from $peerID: name='${file.fileName}', size=${file.fileSize}, mime='${file.mimeType}'")
                        val uniqueMsgId = Uuid.random().toString().uppercase()
                        val savedPath = incomingFileStore.saveIncomingFile(file)
                        val message = BitchatMessage(
                            id = uniqueMsgId,
                            sender = delegate?.getPeerNickname(peerID) ?: "Unknown",
                            content = savedPath,
                            type = messageTypeForMime(file.mimeType),
                            timestamp = Instant.fromEpochMilliseconds(packet.timestamp.toLong()),
                            isRelay = false,
                            isPrivate = true,
                            recipientNickname = delegate?.getMyNickname(),
                            senderPeerID = peerID
                        )

                        Log.d(TAG, "📄 Saved encrypted incoming file to $savedPath (msgId=$uniqueMsgId)")
                        delegate?.onMessageReceived(message)

                        // Send delivery ACK with generated message ID
                        sendDeliveryAck(uniqueMsgId, peerID)
                    } else {
                        Log.w(TAG, "⚠️ Failed to decode encrypted file transfer from $peerID")
                    }
                }
                
                NoisePayloadType.DELIVERED -> {
                    // Handle delivery ACK exactly like iOS
                    val messageID = noisePayload.data.decodeToString()
                    Log.d(TAG, "📬 Delivery ACK received from $peerID for message $messageID")
                    
                    // Simplified: Call delegate with messageID and peerID directly
                    delegate?.onDeliveryAckReceived(messageID, peerID)
                }
                
                NoisePayloadType.READ_RECEIPT -> {
                    // Handle read receipt exactly like iOS
                    val messageID = noisePayload.data.decodeToString()
                    Log.d(TAG, "👁️ Read receipt received from $peerID for message $messageID")
                    
                    // Simplified: Call delegate with messageID and peerID directly
                    delegate?.onReadReceiptReceived(messageID, peerID)
                }
                NoisePayloadType.VERIFY_CHALLENGE -> {
                    Log.d(TAG, "🔐 Verify challenge received from $peerID (${noisePayload.data.size} bytes)")
                    delegate?.onVerifyChallengeReceived(peerID, noisePayload.data, packet.timestamp.toLong())
                }
                NoisePayloadType.VERIFY_RESPONSE -> {
                    Log.d(TAG, "🔐 Verify response received from $peerID (${noisePayload.data.size} bytes)")
                    delegate?.onVerifyResponseReceived(peerID, noisePayload.data, packet.timestamp.toLong())
                }
                NoisePayloadType.VOUCH -> {
                    Log.d(TAG, "🪪 Vouch batch received from $peerID (${noisePayload.data.size} bytes)")
                    delegate?.onVouchAttestationsReceived(peerID, noisePayload.data, packet.timestamp.toLong())
                }
                NoisePayloadType.GROUP_INVITE, NoisePayloadType.GROUP_KEY_UPDATE -> {
                    // Creator-signed group state over the authenticated Noise session; the group
                    // coordinator verifies the creator + applies it. The session peer IS the claimed
                    // sender (Noise-authenticated), so the coordinator can require it to be the creator.
                    val isInvite = noisePayload.type == NoisePayloadType.GROUP_INVITE
                    Log.d(TAG, "👥 Group state (${noisePayload.type}) received from $peerID (${noisePayload.data.size} bytes)")
                    delegate?.onGroupStateReceived(peerID, isInvite, noisePayload.data)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing Noise encrypted message from $peerID: ${e.message}")
        }
    }
    
    /**
     * Handle a directed courier envelope (0x04) addressed to us. If the rotating tag matches our
     * static key we are the recipient — open and deliver the private message like any other.
     * Otherwise a trusted peer is depositing mail for a third party — hand it to the courier
     * coordinator to carry. Envelopes addressed to *other* peers ride the generic relay path and
     * never reach here (this runs only for packets addressed to us).
     */
    fun handleCourierEnvelope(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        if (peerID == myPeerID) return

        val envelope = CourierEnvelope.decode(packet.payload) ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        if (envelope.isExpired(now)) return

        val myKey = delegate?.myNoiseStaticKey() ?: return
        val addressedToUs = CourierEnvelope.candidateTags(myKey, now)
            .any { it.contentEquals(envelope.recipientTag) }
        if (addressedToUs) {
            openCourierEnvelope(envelope, packet)
        } else {
            // A deposit for someone else; the packet sender is the depositor (directed, direct send).
            delegate?.onCourierDeposit(peerID, packet)
        }
    }

    private fun openCourierEnvelope(envelope: CourierEnvelope, packet: BitchatPacket) {
        // v2 (prekey-sealed) envelopes need a one-time prekey we do not hold; the delegate returns
        // null and we drop quietly (we still carried it opaquely for others).
        val opened = delegate?.openCourierPayload(envelope.ciphertext, envelope.prekeyID) ?: return
        val (typedPayload, senderStaticKey) = opened
        val noisePayload = NoisePayload.decode(typedPayload) ?: return
        if (noisePayload.type != NoisePayloadType.PRIVATE_MESSAGE) {
            Log.w(TAG, "⚠️ Courier envelope carried unsupported payload type ${noisePayload.type}")
            return
        }
        val pm = PrivateMessagePacket.decode(noisePayload.data) ?: return

        if (!rememberOpenedCourierMessage(pm.messageID)) {
            Log.d(TAG, "📦 Dropping duplicate courier envelope for message ${pm.messageID.take(8)}…")
            return
        }

        // The sender is usually absent (that is why the message was couriered): prefer a live peerID
        // for their noise key, else address by the full noise-key hex so it lands on the stable
        // favorite thread instead of an unresolvable short id.
        val senderPeerID = delegate?.peerIDForNoiseKey(senderStaticKey) ?: senderStaticKey.toHexString()
        val message = BitchatMessage(
            id = pm.messageID,
            sender = delegate?.getPeerNickname(senderPeerID) ?: senderPeerID.take(8),
            content = pm.content,
            timestamp = Instant.fromEpochMilliseconds(packet.timestamp.toLong()),
            isRelay = false,
            originalSender = null,
            isPrivate = true,
            recipientNickname = delegate?.getMyNickname(),
            senderPeerID = senderPeerID,
            mentions = null,
        )
        Log.d(TAG, "📦 Opened courier envelope from ${senderPeerID.take(8)}…")
        delegate?.onMessageReceived(message)
        // Best-effort ack: only lands if a Noise session with the sender exists (they may be absent),
        // which is fine — the sender's outbox clears on any later delivered/read ack, dedup elsewhere.
        sendDeliveryAck(pm.messageID, senderPeerID)
    }

    /** Records [messageID] as opened; false if already seen. Bounded insertion-ordered eviction. */
    private fun rememberOpenedCourierMessage(messageID: String): Boolean {
        if (!openedCourierMessageIDs.add(messageID)) return false
        while (openedCourierMessageIDs.size > OPENED_COURIER_ID_CAP) {
            openedCourierMessageIDs.remove(openedCourierMessageIDs.iterator().next())
        }
        return true
    }

    /**
     * Send delivery ACK for a received private message - exactly like iOS
     */
    private fun sendDeliveryAck(messageID: String, senderPeerID: String) {
        try {
            // Create ACK payload: [type byte] + [message ID] - exactly like iOS
            val ackPayload = NoisePayload(
                type = NoisePayloadType.DELIVERED,
                data = messageID.encodeToByteArray()
            )
            
            // Encrypt the payload
            val encryptedPayload = delegate?.encryptForPeer(ackPayload.encode(), senderPeerID)
            if (encryptedPayload == null) {
                Log.w(TAG, "Failed to encrypt delivery ACK for $senderPeerID")
                return
            }
            
            // Create NOISE_ENCRYPTED packet exactly like iOS
                val packet = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_ENCRYPTED.value,
                    senderID = peerIdToRoutingBytes(myPeerID),
                    recipientID = peerIdToRoutingBytes(senderPeerID),
                    timestamp = Clock.System.now().toEpochMilliseconds().toULong(),
                    payload = encryptedPayload,
                    signature = null,
                    ttl = MeshConstants.MESSAGE_TTL_HOPS // Same TTL as iOS messageTTL
                )
            
            delegate?.sendPacket(packet)
            Log.d(TAG, "📤 Sent delivery ACK to $senderPeerID for message $messageID")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send delivery ACK to $senderPeerID: ${e.message}")
        }
    }
    
    /**
     * Handle announce message with TLV decoding and signature verification - exactly like iOS
     */
    fun handleAnnounce(routed: RoutedPacket): Boolean {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        if (peerID == myPeerID) return false

        // Ignore stale announcements older than STALE_PEER_TIMEOUT
        val now = Clock.System.now().toEpochMilliseconds()
        val age = now - packet.timestamp.toLong()
        if (age > MeshConstants.Mesh.STALE_PEER_TIMEOUT_MS) {
            Log.w(TAG, "Ignoring stale ANNOUNCE from ${peerID.take(8)} (age=${age}ms > ${MeshConstants.Mesh.STALE_PEER_TIMEOUT_MS}ms)")
            return false
        }
        
        // Try to decode as iOS-compatible IdentityAnnouncement with TLV format
        val announcement = IdentityAnnouncement.decode(packet.payload)
        if (announcement == null) {
            Log.w(TAG, "Failed to decode announce from $peerID as iOS-compatible TLV format")
            return false
        }
        
        // Verify packet signature using the announced signing public key
        var verified = false
        if (packet.signature != null) {
            // Verify that the packet was signed by the signing private key corresponding to the announced signing public key
            verified = delegate?.verifyEd25519Signature(packet.signature!!, packet.toBinaryDataForSigning()!!, announcement.signingPublicKey) ?: false
            if (!verified) {
                Log.w(TAG, "⚠️ Signature verification for announce failed ${peerID.take(8)}")
            }
        }

        // Check for existing peer with different noise public key
        // If existing peer has a different noise public key, do not consider this verified
        val existingPeer = delegate?.getPeerInfo(peerID)
        
        if (existingPeer != null && existingPeer.noisePublicKey != null && !existingPeer.noisePublicKey!!.contentEquals(announcement.noisePublicKey)) {
            Log.w(TAG, "⚠️ Announce key mismatch for ${peerID.take(8)}... — keeping unverified")
            verified = false
        }

        // Require verified announce; ignore otherwise (no backward compatibility)
        if (!verified) {
            Log.w(TAG, "❌ Ignoring unverified announce from ${peerID.take(8)}...")
            return false
        }
        
        // Successfully decoded TLV format exactly like iOS
        Log.d(TAG, "✅ Verified announce from $peerID: nickname=${announcement.nickname}, " +
                "noisePublicKey=${announcement.noisePublicKey.toHexString().take(16)}..., " +
                "signingPublicKey=${announcement.signingPublicKey.toHexString().take(16)}...")
        
        // Extract nickname and public keys from TLV data
        val nickname = announcement.nickname
        val noisePublicKey = announcement.noisePublicKey
        val signingPublicKey = announcement.signingPublicKey
        
        // Update peer info with verification status through new method. An announce without the
        // capabilities TLV resets the peer to the empty set rather than keeping stale bits — a
        // peer that stops advertising a feature has stopped offering it (reference parity).
        val isFirstAnnounce = delegate?.updatePeerInfo(
            peerID = peerID,
            nickname = nickname,
            noisePublicKey = noisePublicKey,
            signingPublicKey = signingPublicKey,
            isVerified = true,
            capabilities = announcement.capabilities ?: PeerCapabilities.NONE,
        ) ?: false

        // Update peer ID binding with noise public key for identity management
        delegate?.updatePeerIDBinding(
            newPeerID = peerID,
            nickname = nickname,
            publicKey = noisePublicKey,
            previousPeerID = null
        )
        
        // Update mesh graph from gossip neighbors (only if TLV present)
        try {
            val neighborsOrNull = GossipTLV.decodeNeighborsFromAnnouncementPayload(packet.payload)
            meshGraphService.updateFromAnnouncement(peerID, nickname, neighborsOrNull, packet.timestamp)
        } catch (_: Exception) { }

        Log.d(TAG, "✅ Processed verified TLV announce: stored identity for $peerID")
        return isFirstAnnounce
    }
    
    /**
     * Handle Noise handshake - SIMPLIFIED iOS-compatible version
     * Single handshake type (0x10) with response determined by payload analysis
     */
    fun handleNoiseHandshake(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        
        Log.d(TAG, "Processing Noise handshake from $peerID (${packet.payload.size} bytes)")
        
        // Skip our own handshake messages
        if (peerID == myPeerID) return
        
        // Check if handshake is addressed to us
        val recipientID = packet.recipientID?.toHexString()
        if (recipientID != myPeerID) {
            Log.d(TAG, "Handshake not for me (for $recipientID, I am $myPeerID)")
            return
        }
        
        try {
            // Process handshake message through delegate (simplified approach)
            val response = delegate?.processNoiseHandshakeMessage(packet.payload, peerID)
            
            if (response != null) {
                Log.d(TAG, "Generated handshake response for $peerID (${response.size} bytes)")
                
                // Send response using same packet type (simplified iOS approach)
                val responsePacket = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_HANDSHAKE.value,
                    senderID = peerIdToRoutingBytes(myPeerID),
                    recipientID = peerIdToRoutingBytes(peerID),
                    timestamp = Clock.System.now().toEpochMilliseconds().toULong(),
                    payload = response,
                    signature = null,
                    ttl = MeshConstants.MESSAGE_TTL_HOPS // Same TTL as iOS
                )
                
                delegate?.sendPacket(responsePacket)
                Log.d(TAG, "📤 Sent handshake response to $peerID")
            }
            
            // Check if session is now established
            val hasSession = delegate?.hasNoiseSession(peerID) ?: false
            if (hasSession) {
                Log.d(TAG, "✅ Noise session established with $peerID")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process Noise handshake from $peerID: ${e.message}")
        }
    }
    
    /**
     * Handle broadcast or private message
     */
    fun handleMessage(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        if (peerID == myPeerID) return
        val senderNickname = delegate?.getPeerNickname(peerID)
        if (senderNickname != null) {
            Log.d(TAG, "Received message from $senderNickname")
            delegate?.updatePeerNickname(peerID, senderNickname)
        }
        
        val recipientID = packet.recipientID?.takeIf { !it.contentEquals(delegate?.getBroadcastRecipient()) }
        
        if (recipientID == null) {
            // BROADCAST MESSAGE
            handleBroadcastMessage(routed)
        } else if (recipientID.toHexString() == myPeerID) {
            // PRIVATE MESSAGE FOR US
            handlePrivateMessage(packet, peerID)
        }
        // Message relay is now handled by centralized PacketRelayManager
    }
    
    /**
     * Handle broadcast message with verification enforcement
     */
    private fun handleBroadcastMessage(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        
        // Enforce: only accept public messages from verified peers we know
        val peerInfo = delegate?.getPeerInfo(peerID)
        if (peerInfo == null || !peerInfo.isVerifiedNickname) {
            Log.w(TAG, "🚫 Dropping public message from unverified or unknown peer ${peerID.take(8)}...")
            return
        }
        
        try {
            // Try file packet first (voice, image, etc.) and log outcome for FILE_TRANSFER
            val isFileTransfer = MessageType.fromValue(packet.type) == MessageType.FILE_TRANSFER
            val file = BitchatFilePacket.decode(packet.payload)
            if (file != null) {
                if (isFileTransfer) {
                    Log.d(TAG, "📥 FILE_TRANSFER decode success (broadcast): name='${file.fileName}', size=${file.fileSize}, mime='${file.mimeType}', from=${peerID.take(8)}")
                }
                val savedPath = incomingFileStore.saveIncomingFile(file)
                val message = BitchatMessage(
                    // Stable content-derived id: request-sync replays of the same packet
                    // must collapse to one message (upstream #707)
                    id = PacketIdUtil.computeIdHex(packet).uppercase(),
                    sender = delegate?.getPeerNickname(peerID) ?: "unknown",
                    content = savedPath,
                    type = messageTypeForMime(file.mimeType),
                    senderPeerID = peerID,
                    timestamp = Instant.fromEpochMilliseconds(packet.timestamp.toLong())
                )
                Log.d(TAG, "📄 Saved incoming file to $savedPath")
                delegate?.onMessageReceived(message)
                return
            } else if (isFileTransfer) {
                Log.w(TAG, "⚠️ FILE_TRANSFER decode failed (broadcast) from ${peerID.take(8)} payloadSize=${packet.payload.size}")
            }

            // Fallback: plain text
            val message = BitchatMessage(
                id = PacketIdUtil.computeIdHex(packet).uppercase(),
                sender = delegate?.getPeerNickname(peerID) ?: "unknown",
                content = packet.payload.decodeToString(),
                senderPeerID = peerID,
                timestamp = Instant.fromEpochMilliseconds(packet.timestamp.toLong())
            )
            delegate?.onMessageReceived(message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process broadcast message: ${e.message}")
        }
    }
    
    /**
     * Handle (decrypted) private message addressed to us
     */
    private fun handlePrivateMessage(packet: BitchatPacket, peerID: String) {
        try {
            // Verify signature if present. A null delegate must not NPE here; treat an
            // unverifiable signature as invalid and drop.
            if (packet.signature != null && delegate?.verifySignature(packet, peerID) != true) {
                Log.w(TAG, "Invalid signature for private message from $peerID")
                return
            }

            // Try file packet first (voice, image, etc.) and log outcome for FILE_TRANSFER
            val isFileTransfer = MessageType.fromValue(packet.type) == MessageType.FILE_TRANSFER
            val file = BitchatFilePacket.decode(packet.payload)
            if (file != null) {
                if (isFileTransfer) {
                    Log.d(TAG, "📥 FILE_TRANSFER decode success (private): name='${file.fileName}', size=${file.fileSize}, mime='${file.mimeType}', from=${peerID.take(8)}")
                }
                val savedPath = incomingFileStore.saveIncomingFile(file)
                val message = BitchatMessage(
                    id = Uuid.random().toString().uppercase(),
                    sender = delegate?.getPeerNickname(peerID) ?: "unknown",
                    content = savedPath,
                    type = messageTypeForMime(file.mimeType),
                    senderPeerID = peerID,
                    timestamp = Instant.fromEpochMilliseconds(packet.timestamp.toLong()),
                    isPrivate = true,
                    recipientNickname = delegate?.getMyNickname()
                )
                Log.d(TAG, "📄 Saved incoming file to $savedPath")
                delegate?.onMessageReceived(message)
                return
            } else if (isFileTransfer) {
                Log.w(TAG, "⚠️ FILE_TRANSFER decode failed (private) from ${peerID.take(8)} payloadSize=${packet.payload.size}")
            }

            // Fallback: plain text
            val message = BitchatMessage(
                sender = delegate?.getPeerNickname(peerID) ?: "unknown",
                content = packet.payload.decodeToString(),
                senderPeerID = peerID,
                timestamp = Instant.fromEpochMilliseconds(packet.timestamp.toLong())
            )
            delegate?.onMessageReceived(message)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process private message from $peerID: ${e.message}")
        }
    }

    
    
    /**
     * Handle leave message
     */
    fun handleLeave(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        val content = packet.payload.decodeToString()
        
        if (content.startsWith("#")) {
            // Channel leave
            delegate?.onChannelLeave(content, peerID)
        } else {
            // Peer disconnect
            delegate?.removePeer(peerID)
        }
        
        // Leave message relay is now handled by centralized PacketRelayManager
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Message Handler Debug Info ===")
            appendLine("Handler Scope Active: ${handlerScope.isActive}")
            appendLine("My Peer ID: $myPeerID")
        }
    }

    /**
     * Shutdown the handler
     */
    fun shutdown() {
        handlerScope.cancel()
    }

    /**
     * Handle favorite/unfavorite notification received over mesh as a private message.
     * Content format: "[FAVORITED]:npub..." or "[UNFAVORITED]:npub..."
     */
    private fun handleFavoriteNotificationFromMesh(content: String, fromPeerID: String) {
        try {
            val isFavorite = content.startsWith("[FAVORITED]")
            val npub = content.substringAfter(":", "").trim().takeIf { it.startsWith("npub1") }

            // Update mutual favorite status in persistence
            // Resolve full Noise key if available via delegate peer info
            val peerInfo = delegate?.getPeerInfo(fromPeerID)
            val noiseKey = peerInfo?.noisePublicKey
            if (noiseKey != null) {
                favoriteNostrLink?.updatePeerFavoritedUs(noiseKey, isFavorite)
                if (npub != null) {
                    // Index by noise key and current mesh peerID for fast Nostr routing
                    favoriteNostrLink?.updateNostrPublicKey(noiseKey, npub)
                    favoriteNostrLink?.updateNostrPublicKeyForPeerId(fromPeerID, npub)
                }

                // Determine iOS-style guidance text
                val theyFavorite = favoriteNostrLink?.isFavorite(noiseKey) == true
                val guidance = if (isFavorite) {
                    if (theyFavorite) {
                        " — mutual! You can continue DMs via Nostr when out of mesh."
                    } else {
                        " — favorite back to continue DMs later."
                    }
                } else {
                    ". DMs over Nostr will pause unless you both favorite again."
                }

                // Emit system message via delegate callback
                val action = if (isFavorite) "favorited" else "unfavorited"
                val sys = BitchatMessage(
                    sender = "system",
                    content = "${peerInfo.nickname} $action you$guidance",
                    timestamp = Clock.System.now(),
                    isRelay = false
                )
                delegate?.onMessageReceived(sys)
            }
        } catch (_: Exception) {
            // Best-effort; ignore errors
        }
    }
}

/**
 * Delegate interface for message handler callbacks
 */
internal interface MessageHandlerDelegate {
    // Peer management
    fun addOrUpdatePeer(peerID: String, nickname: String): Boolean
    fun removePeer(peerID: String)
    fun updatePeerNickname(peerID: String, nickname: String)
    fun getPeerNickname(peerID: String): String?
    fun getNetworkSize(): Int
    fun getMyNickname(): String?
    fun getPeerInfo(peerID: String): PeerInfo?
    fun updatePeerInfo(
        peerID: String,
        nickname: String,
        noisePublicKey: ByteArray,
        signingPublicKey: ByteArray,
        isVerified: Boolean,
        capabilities: PeerCapabilities = PeerCapabilities.NONE,
    ): Boolean
    
    // Packet operations
    fun sendPacket(packet: BitchatPacket)
    fun relayPacket(routed: RoutedPacket)
    fun getBroadcastRecipient(): ByteArray
    
    // Cryptographic operations
    fun verifySignature(packet: BitchatPacket, peerID: String): Boolean
    fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray?
    fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String): ByteArray?
    fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean
    
    // Noise protocol operations
    fun hasNoiseSession(peerID: String): Boolean
    fun initiateNoiseHandshake(peerID: String)
    fun processNoiseHandshakeMessage(payload: ByteArray, peerID: String): ByteArray?
    fun updatePeerIDBinding(newPeerID: String, nickname: String,
                           publicKey: ByteArray, previousPeerID: String?)
    
    // Message operations
    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?

    // Callbacks
    fun onMessageReceived(message: BitchatMessage)
    fun onChannelLeave(channel: String, fromPeer: String)
    fun onDeliveryAckReceived(messageID: String, peerID: String)
    fun onReadReceiptReceived(messageID: String, peerID: String)
    fun onVerifyChallengeReceived(peerID: String, payload: ByteArray, timestampMs: Long)
    fun onVerifyResponseReceived(peerID: String, payload: ByteArray, timestampMs: Long)
    fun onVouchAttestationsReceived(peerID: String, payload: ByteArray, timestampMs: Long)

    // Courier store-and-forward (0x04)
    /** Our own Noise static public key, for computing our courier recipient tags. */
    fun myNoiseStaticKey(): ByteArray?
    /**
     * Open a courier envelope sealed to our static key: returns (typed payload, sender static key), or
     * null when it is not addressed to us / malformed / a v2 prekey seal we cannot open ([prekeyID] set).
     */
    fun openCourierPayload(ciphertext: ByteArray, prekeyID: UInt?): Pair<ByteArray, ByteArray>?
    /** A currently-known peerID whose Noise static key is [noiseKey], or null if none is on the mesh. */
    fun peerIDForNoiseKey(noiseKey: ByteArray): String?
    /** A trusted peer deposited an envelope for a third party; carry it if policy allows. */
    fun onCourierDeposit(fromPeerID: String, packet: BitchatPacket)

    /** Creator-signed group state (0x06 invite / 0x07 update) over [fromPeerID]'s Noise session. */
    fun onGroupStateReceived(fromPeerID: String, isInvite: Boolean, payload: ByteArray)
}
