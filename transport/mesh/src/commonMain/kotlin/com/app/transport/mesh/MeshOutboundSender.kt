@file:OptIn(ExperimentalUuidApi::class)

package com.app.transport.mesh

import com.app.common.encoding.hexEncodedString
import com.app.common.utils.Log
import com.app.crypto.EncryptionService
import com.app.transport.crypto.Sha256
import com.app.transport.GeohashReadReceiptRouter
import com.app.transport.MeshConstants
import com.app.transport.NicknameSource
import com.app.transport.SeenMessageStore
import com.app.transport.VerificationService
import com.app.transport.meshgraph.MeshGraphService
import com.app.transport.meshgraph.RoutePlanner
import com.app.transport.model.BitchatFilePacket
import com.app.transport.model.IdentityAnnouncement
import com.app.transport.model.PeerCapabilities
import com.app.transport.model.NoisePayload
import com.app.transport.model.NoisePayloadType
import com.app.transport.model.PrivateMessagePacket
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.peerIdToRoutingBytes
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.SpecialRecipients
import com.app.transport.sync.GossipSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Outbound send path of the mesh engine: builds, signs and broadcasts every packet the
 * local node originates (public/private messages, files, receipts, verify TLVs, announces,
 * leave). Extracted from [BluetoothMeshService]; one instance per component generation —
 * rebuilt together with PeerManager/GossipSyncManager on reset/revival.
 */
internal class MeshOutboundSender(
    private val myPeerID: String,
    private val encryptionService: EncryptionService,
    private val meshNetwork: MeshNetwork,
    private val meshGraphService: MeshGraphService,
    private val peerManager: PeerManager,
    private val gossipSyncManager: GossipSyncManager,
    private val nicknameSource: NicknameSource,
    private val seenMessageStore: SeenMessageStore,
    private val geohashReadReceiptRouter: GeohashReadReceiptRouter,
    private val verificationService: VerificationService,
    private val scope: CoroutineScope,
    private val initiateHandshake: (String) -> Unit,
    private val gatewayEnabled: () -> Boolean = { false },
) {

    companion object {
        private const val TAG = "MeshOutboundSender"
        private val MAX_TTL: UByte = MeshConstants.MESSAGE_TTL_HOPS
    }

    /**
     * Send public message
     */
    fun sendMessage(content: String, mentions: List<String>, channel: String?) {
        if (content.isEmpty()) return

        scope.launch {
            val packet = BitchatPacket(
                version = 1u,
                type = MessageType.MESSAGE.value,
                senderID = peerIdToRoutingBytes(myPeerID),
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = epochMillis().toULong(),
                payload = content.encodeToByteArray(),
                signature = null,
                ttl = MAX_TTL
            )

            // Sign the packet before broadcasting
            val signedPacket = signPacketBeforeBroadcast(packet)
            meshNetwork.broadcast(RoutedPacket(signedPacket))
            // Track our own broadcast message for sync
            try { gossipSyncManager.onPublicPacketSeen(signedPacket) } catch (_: Exception) { }
        }
    }

    /**
     * Send a file over mesh as a broadcast MESSAGE (public mesh timeline/channels).
     */
    fun sendFileBroadcast(file: BitchatFilePacket) {
        try {
            Log.d(TAG, "📤 sendFileBroadcast: name=${file.fileName}, size=${file.fileSize}")
            val payload = file.encode()
            if (payload == null) {
                Log.e(TAG, "❌ Failed to encode file packet in sendFileBroadcast")
                return
            }
            Log.d(TAG, "📦 Encoded payload: ${payload.size} bytes")
            scope.launch {
                val packet = BitchatPacket(
                    version = 2u,  // FILE_TRANSFER uses v2 for 4-byte payload length to support large files
                    type = MessageType.FILE_TRANSFER.value,
                    senderID = peerIdToRoutingBytes(myPeerID),
                    recipientID = SpecialRecipients.BROADCAST,
                    timestamp = epochMillis().toULong(),
                    payload = payload,
                    signature = null,
                    ttl = MAX_TTL
                )
                val signed = signPacketBeforeBroadcast(packet)
                // Use a stable transferId based on the file TLV payload for progress tracking
                val transferId = sha256Hex(payload)
                meshNetwork.broadcast(RoutedPacket(signed, transferId = transferId))
                try { gossipSyncManager.onPublicPacketSeen(signed) } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ sendFileBroadcast failed: ${e.message}", e)
            Log.e(TAG, "❌ File: name=${file.fileName}, size=${file.fileSize}")
        }
    }

    /**
     * Send a file as an encrypted private message using Noise protocol
     */
    fun sendFilePrivate(recipientPeerID: String, file: BitchatFilePacket) {
        try {
            Log.d(TAG, "📤 sendFilePrivate (ENCRYPTED): to=$recipientPeerID, name=${file.fileName}, size=${file.fileSize}")

            scope.launch {
                // Check if we have an established Noise session
                if (encryptionService.hasEstablishedSession(recipientPeerID)) {
                    try {
                        // Encode the file packet as TLV
                        val filePayload = file.encode()
                        if (filePayload == null) {
                            Log.e(TAG, "❌ Failed to encode file packet for private send")
                            return@launch
                        }
                        Log.d(TAG, "📦 Encoded file TLV: ${filePayload.size} bytes")

                        // Create NoisePayload wrapper (type byte + file TLV data) - same as iOS
                        val noisePayload = NoisePayload(
                            type = NoisePayloadType.FILE_TRANSFER,
                            data = filePayload
                        )

                        // Encrypt the payload using Noise
                        val encrypted = encryptionService.encrypt(noisePayload.encode(), recipientPeerID)
                        if (encrypted == null) {
                            Log.e(TAG, "❌ Failed to encrypt file for $recipientPeerID")
                            return@launch
                        }
                        Log.d(TAG, "🔐 Encrypted file payload: ${encrypted.size} bytes")

                        // Create NOISE_ENCRYPTED packet (not FILE_TRANSFER!)
                        val packet = BitchatPacket(
                            version = 1u,
                            type = MessageType.NOISE_ENCRYPTED.value,
                            senderID = peerIdToRoutingBytes(myPeerID),
                            recipientID = peerIdToRoutingBytes(recipientPeerID),
                            timestamp = epochMillis().toULong(),
                            payload = encrypted,
                            signature = null,
                            ttl = MeshConstants.MESSAGE_TTL_HOPS
                        )

                        // Sign and send the encrypted packet
                        val signed = signPacketBeforeBroadcast(packet)
                        // Use a stable transferId based on the unencrypted file TLV payload for progress tracking
                        val transferId = sha256Hex(filePayload)
                        meshNetwork.broadcast(RoutedPacket(signed, transferId = transferId))
                        Log.d(TAG, "✅ Sent encrypted file to $recipientPeerID")

                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to encrypt file for $recipientPeerID: ${e.message}", e)
                    }
                } else {
                    // No session - initiate handshake but don't queue file
                    Log.w(TAG, "⚠️ No Noise session with $recipientPeerID for file transfer, initiating handshake")
                    initiateHandshake(recipientPeerID)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ sendFilePrivate failed: ${e.message}", e)
            Log.e(TAG, "❌ File: to=$recipientPeerID, name=${file.fileName}, size=${file.fileSize}")
        }
    }

    /**
     * Send private message - SIMPLIFIED iOS-compatible version
     * Uses NoisePayloadType system exactly like iOS SimplifiedBluetoothService
     */
    fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String?) {
        if (content.isEmpty() || recipientPeerID.isEmpty()) return
        if (recipientNickname.isEmpty()) return

        scope.launch {
            val finalMessageID = messageID ?: Uuid.random().toString()

            Log.d(TAG, "📨 Sending PM to $recipientPeerID: ${content.take(30)}...")

            // Check if we have an established Noise session
            if (encryptionService.hasEstablishedSession(recipientPeerID)) {
                try {
                    // Create TLV-encoded private message exactly like iOS
                    val privateMessage = PrivateMessagePacket(
                        messageID = finalMessageID,
                        content = content
                    )

                    val tlvData = privateMessage.encode()
                    if (tlvData == null) {
                        Log.e(TAG, "Failed to encode private message with TLV")
                        return@launch
                    }

                    // Create message payload with NoisePayloadType prefix: [type byte] + [TLV data]
                    val messagePayload = NoisePayload(
                        type = NoisePayloadType.PRIVATE_MESSAGE,
                        data = tlvData
                    )

                    // Encrypt the payload
                    val encrypted = encryptionService.encrypt(messagePayload.encode(), recipientPeerID)

                    // Create NOISE_ENCRYPTED packet exactly like iOS
                    val packet = BitchatPacket(
                        version = 1u,
                        type = MessageType.NOISE_ENCRYPTED.value,
                        senderID = peerIdToRoutingBytes(myPeerID),
                        recipientID = peerIdToRoutingBytes(recipientPeerID),
                        timestamp = epochMillis().toULong(),
                        payload = encrypted,
                        signature = null,
                        ttl = MAX_TTL
                    )

                    // Sign the packet before broadcasting
                    val signedPacket = signPacketBeforeBroadcast(packet)
                    meshNetwork.broadcast(RoutedPacket(signedPacket))
                    Log.d(TAG, "📤 Sent encrypted private message to $recipientPeerID (${encrypted.size} bytes)")

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to encrypt private message for $recipientPeerID: ${e.message}")
                }
            } else {
                // Fire and forget - initiate handshake but don't queue exactly like iOS
                Log.d(TAG, "🤝 No session with $recipientPeerID, initiating handshake")
                initiateHandshake(recipientPeerID)
            }
        }
    }

    /**
     * Send read receipt for a received private message - NoisePayloadType implementation
     * Uses same encryption approach as iOS SimplifiedBluetoothService
     */
    fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) {
        scope.launch {
            Log.d(TAG, "📖 Sending read receipt for message $messageID to $recipientPeerID")

            // Route geohash read receipts via the relay (resolved + routed in the app layer)
            if (geohashReadReceiptRouter.routeIfGeohashAlias(messageID, recipientPeerID)) {
                return@launch
            }

            try {
                // Avoid duplicate read receipts: check persistent store first
                if (seenMessageStore.hasRead(messageID)) {
                    Log.d(TAG, "Skipping read receipt for $messageID - already marked read")
                    return@launch
                }

                // Create read receipt payload using NoisePayloadType exactly like iOS
                val readReceiptPayload = NoisePayload(
                    type = NoisePayloadType.READ_RECEIPT,
                    data = messageID.encodeToByteArray()
                )

                // Encrypt the payload
                val encrypted = encryptionService.encrypt(readReceiptPayload.encode(), recipientPeerID)

                // Create NOISE_ENCRYPTED packet exactly like iOS
                val packet = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_ENCRYPTED.value,
                    senderID = peerIdToRoutingBytes(myPeerID),
                    recipientID = peerIdToRoutingBytes(recipientPeerID),
                    timestamp = epochMillis().toULong(),
                    payload = encrypted,
                    signature = null,
                    ttl = MeshConstants.MESSAGE_TTL_HOPS // Same TTL as iOS messageTTL
                )

                // Sign the packet before broadcasting
                val signedPacket = signPacketBeforeBroadcast(packet)
                meshNetwork.broadcast(RoutedPacket(signedPacket))
                Log.d(TAG, "📤 Sent read receipt to $recipientPeerID for message $messageID")

                // Persist as read after successful send
                seenMessageStore.markRead(messageID)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to send read receipt to $recipientPeerID: ${e.message}")
            }
        }
    }

    // MARK: QR Verification over Noise

    fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        val tlv = verificationService.buildVerifyChallenge(noiseKeyHex, nonceA)
        val payload = NoisePayload(
            type = NoisePayloadType.VERIFY_CHALLENGE,
            data = tlv
        )
        sendNoisePayloadToPeer(payload, peerID, "verify challenge")
    }

    fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        val tlv = verificationService.buildVerifyResponse(noiseKeyHex, nonceA) ?: return
        val payload = NoisePayload(
            type = NoisePayloadType.VERIFY_RESPONSE,
            data = tlv
        )
        sendNoisePayloadToPeer(payload, peerID, "verify response")
    }

    // MARK: Transitive Verification (vouch)

    /** [batchPayload] is an already-encoded `VouchAttestation.encodeList` body. */
    fun sendVouchAttestations(batchPayload: ByteArray, peerID: String) {
        val payload = NoisePayload(
            type = NoisePayloadType.VOUCH,
            data = batchPayload
        )
        sendNoisePayloadToPeer(payload, peerID, "vouch attestations")
    }

    /**
     * Send an encoded CourierEnvelope as a signed, directed 0x04 packet. Unlike a Noise payload this
     * is not session-encrypted (the envelope ciphertext is its own one-way seal); the Ed25519 packet
     * signature lets a courier authenticate the depositor. Directed by recipientID — routing floods it
     * toward the target exactly like a directed DM.
     */
    fun sendCourierEnvelope(payload: ByteArray, toPeerID: String) {
        scope.launch {
            try {
                val packet = BitchatPacket(
                    version = 1u,
                    type = MessageType.COURIER_ENVELOPE.value,
                    senderID = peerIdToRoutingBytes(myPeerID),
                    recipientID = peerIdToRoutingBytes(toPeerID),
                    timestamp = epochMillis().toULong(),
                    payload = payload,
                    signature = null,
                    ttl = MAX_TTL
                )
                val signedPacket = signPacketBeforeBroadcast(packet)
                meshNetwork.broadcast(RoutedPacket(signedPacket))
                Log.d(TAG, "📦 Sent courier envelope to $toPeerID (${payload.size} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send courier envelope to $toPeerID: ${e.message}")
            }
        }
    }

    // MARK: Private groups (0x25 broadcast; 0x06 / 0x07 state over Noise)

    /**
     * Broadcast a sealed group message (0x25) like a public message. The outer packet is intentionally
     * unsigned — receivers authenticate the sender's Ed25519 signature inside the ciphertext, which
     * still verifies for gossip-backfilled copies after the sender's announce has expired. A relayed
     * self-copy is dropped by the coordinator's own-signing-key check, so no packet-level self-dedup.
     */
    fun broadcastGroupMessage(payload: ByteArray) {
        if (payload.isEmpty()) return
        scope.launch {
            val packet = BitchatPacket(
                version = 1u,
                type = MessageType.GROUP_MESSAGE.value,
                senderID = peerIdToRoutingBytes(myPeerID),
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = epochMillis().toULong(),
                payload = payload,
                signature = null,
                ttl = MAX_TTL,
            )
            meshNetwork.broadcast(RoutedPacket(packet))
            try { gossipSyncManager.onPublicPacketSeen(packet) } catch (_: Exception) { }
            Log.d(TAG, "👥 Broadcast group message (${payload.size} bytes)")
        }
    }

    /** Send creator-signed group state 1:1 over the peer's Noise session (0x06 invite / 0x07 update). */
    fun sendGroupState(payload: ByteArray, toPeerID: String, isInvite: Boolean) {
        val type = if (isInvite) NoisePayloadType.GROUP_INVITE else NoisePayloadType.GROUP_KEY_UPDATE
        sendNoisePayloadToPeer(NoisePayload(type, payload), toPeerID, "group state")
    }

    // MARK: Geohash boards (0x23 broadcast)

    /**
     * Broadcast a signed board payload (post or tombstone) as a public 0x23 packet. Signed like the
     * reference (outer sig is a nominal first-hop marker); authenticity is enforced by the inner
     * author signature that survives multi-hop relay + gossip backfill.
     */
    fun sendBoardPayload(payload: ByteArray) {
        if (payload.isEmpty()) return
        scope.launch {
            val packet = BitchatPacket(
                version = 1u,
                type = MessageType.BOARD_POST.value,
                senderID = peerIdToRoutingBytes(myPeerID),
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = epochMillis().toULong(),
                payload = payload,
                signature = null,
                ttl = MAX_TTL,
            )
            val signed = signPacketBeforeBroadcast(packet)
            meshNetwork.broadcast(RoutedPacket(signed))
            try { gossipSyncManager.onPublicPacketSeen(signed) } catch (_: Exception) { }
            Log.d(TAG, "📋 Broadcast board payload (${payload.size} bytes)")
        }
    }

    // MARK: One-time prekey bundles (0x24 broadcast)

    /**
     * Broadcast a signed prekey bundle (0x24) as a public packet. Signed so receivers can verify the
     * outer packet against the owner's announce-bound signing key (defeating replay under a spoofed
     * sender); the inner bundle signature is the primary gate and survives multi-hop relay.
     */
    fun sendPrekeyBundle(payload: ByteArray) {
        if (payload.isEmpty()) return
        scope.launch {
            val packet = BitchatPacket(
                version = 1u,
                type = MessageType.PREKEY_BUNDLE.value,
                senderID = peerIdToRoutingBytes(myPeerID),
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = epochMillis().toULong(),
                payload = payload,
                signature = null,
                ttl = MAX_TTL,
            )
            val signed = signPacketBeforeBroadcast(packet)
            meshNetwork.broadcast(RoutedPacket(signed))
            Log.d(TAG, "🔑 Broadcast prekey bundle (${payload.size} bytes)")
        }
    }

    fun sendNostrCarrier(payload: ByteArray, toPeerID: String): Boolean {
        if (payload.isEmpty()) return false
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.NOSTR_CARRIER.value,
            senderID = peerIdToRoutingBytes(myPeerID),
            recipientID = peerIdToRoutingBytes(toPeerID),
            timestamp = epochMillis().toULong(),
            payload = payload,
            signature = null,
            ttl = MAX_TTL,
        )
        // A carrier commonly contains a full Nostr event and therefore exceeds a BLE frame.
        // The queued directed path preserves the recipient while routing every fragment through
        // the shared fragmentation and back-pressure policy.
        return meshNetwork.sendToPeerQueued(toPeerID, RoutedPacket(packet)) != SendPath.NoRoute
    }

    fun broadcastNostrCarrier(payload: ByteArray) {
        if (payload.isEmpty()) return
        val packet = BitchatPacket(
            version = 1u, type = MessageType.NOSTR_CARRIER.value,
            senderID = peerIdToRoutingBytes(myPeerID), recipientID = SpecialRecipients.BROADCAST,
            timestamp = epochMillis().toULong(), payload = payload, signature = null, ttl = MAX_TTL,
        )
        meshNetwork.broadcast(RoutedPacket(packet))
    }

    private fun sendNoisePayloadToPeer(payload: NoisePayload, recipientPeerID: String, label: String) {
        scope.launch {
            try {
                val encrypted = encryptionService.encrypt(payload.encode(), recipientPeerID)
                val packet = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_ENCRYPTED.value,
                    senderID = peerIdToRoutingBytes(myPeerID),
                    recipientID = peerIdToRoutingBytes(recipientPeerID),
                    timestamp = epochMillis().toULong(),
                    payload = encrypted,
                    signature = null,
                    ttl = MeshConstants.MESSAGE_TTL_HOPS
                )

                val signedPacket = signPacketBeforeBroadcast(packet)
                meshNetwork.broadcast(RoutedPacket(signedPacket))
                Log.d(TAG, "📤 Sent $label to $recipientPeerID (${payload.data.size} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send $label to $recipientPeerID: ${e.message}")
            }
        }
    }

    /**
     * Send broadcast announce with TLV-encoded identity announcement - exactly like iOS
     */
    fun sendBroadcastAnnounce() {
        Log.d(TAG, "Sending broadcast announce")
        scope.launch {
            val tlvPayload = buildAnnouncePayload() ?: return@launch

            val announcePacket = BitchatPacket(
                type = MessageType.ANNOUNCE.value,
                ttl = MAX_TTL,
                senderID = myPeerID,
                payload = tlvPayload
            )

            // Sign the packet using our signing key (exactly like iOS)
            val signedPacket = encryptionService.signData(announcePacket.toBinaryDataForSigning()!!)?.let { signature ->
                announcePacket.copy(signature = signature)
            } ?: announcePacket

            meshNetwork.broadcast(RoutedPacket(signedPacket))
            Log.d(TAG, "Sent iOS-compatible signed TLV announce (${tlvPayload.size} bytes)")
            // Track announce for sync
            try { gossipSyncManager.onPublicPacketSeen(signedPacket) } catch (_: Exception) { }
        }
    }

    /**
     * Send announcement to specific peer with TLV-encoded identity announcement - exactly like iOS
     */
    fun sendAnnouncementToPeer(peerID: String) {
        if (peerManager.hasAnnouncedToPeer(peerID)) return

        val tlvPayload = buildAnnouncePayload() ?: return

        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = MAX_TTL,
            senderID = myPeerID,
            payload = tlvPayload
        )

        // Sign the packet using our signing key (exactly like iOS)
        val signedPacket = encryptionService.signData(packet.toBinaryDataForSigning()!!)?.let { signature ->
            packet.copy(signature = signature)
        } ?: packet

        meshNetwork.broadcast(RoutedPacket(signedPacket))
        peerManager.markPeerAsAnnouncedTo(peerID)
        Log.d(TAG, "Sent iOS-compatible signed TLV peer announce to $peerID (${tlvPayload.size} bytes)")

        // Track announce for sync
        try { gossipSyncManager.onPublicPacketSeen(signedPacket) } catch (_: Exception) { }
    }

    /**
     * Builds the announce TLV payload: identity announcement plus a gossip TLV of up to 10
     * direct neighbors. Also refreshes our own node in the mesh graph. Returns null when no
     * identity keys are available (nothing to announce).
     */
    private fun buildAnnouncePayload(): ByteArray? {
        val nickname = try { nicknameSource.nickname(myPeerID) } catch (_: Exception) { myPeerID }

        // Get the static public key for the announcement
        val staticKey = encryptionService.getStaticPublicKey()
        if (staticKey == null) {
            Log.e(TAG, "No static public key available for announcement")
            return null
        }

        // Get the signing public key for the announcement
        val signingKey = encryptionService.getSigningPublicKey()
        if (signingKey == null) {
            Log.e(TAG, "No signing public key available for announcement")
            return null
        }

        // Create iOS-compatible IdentityAnnouncement with TLV encoding. We advertise only what we
        // actually implement; while that set is empty the 0x05 TLV is omitted entirely, leaving the
        // announce bytes unchanged from before capabilities existed.
        val advertised = PeerCapabilities.localSupported(gatewayEnabled()).takeIf { !it.isEmpty() }
        val announcement = IdentityAnnouncement(nickname, staticKey, signingKey, advertised)
        val encoded = announcement.encode()
        if (encoded == null) {
            Log.e(TAG, "Failed to encode announcement as TLV")
            return null
        }
        var tlvPayload: ByteArray = encoded

        // Append gossip TLV containing up to 10 direct neighbors (compact IDs)
        try {
            val directPeers = getDirectPeerIDsForGossip()
            if (directPeers.isNotEmpty()) {
                val gossip = com.app.transport.meshgraph.GossipTLV.encodeNeighbors(directPeers)
                tlvPayload = tlvPayload + gossip
            }
            // Always update our own node in the mesh graph with the neighbor list we used
            try {
                meshGraphService
                    .updateFromAnnouncement(myPeerID, nickname, directPeers, epochMillis().toULong())
            } catch (_: Exception) { }
        } catch (_: Exception) { }

        return tlvPayload
    }

    /**
     * Collect up to 10 direct neighbors for gossip TLV.
     */
    private fun getDirectPeerIDsForGossip(): List<String> {
        return try {
            // Prefer verified peers that are currently marked as direct
            val verified = peerManager.getVerifiedPeers()
            val direct = verified.filter { it.value.isDirectConnection }.keys.toList()
            direct.take(10)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Send leave announcement
     */
    fun sendLeaveAnnouncement() {
        val packet = BitchatPacket(
            type = MessageType.LEAVE.value,
            ttl = MAX_TTL,
            senderID = myPeerID,
            payload = byteArrayOf()
        )

        // Sign the packet before broadcasting
        val signedPacket = signPacketBeforeBroadcast(packet)
        meshNetwork.broadcast(RoutedPacket(signedPacket))
    }

    /**
     * Sign packet before broadcasting using our signing private key
     */
    fun signPacketBeforeBroadcast(packet: BitchatPacket): BitchatPacket {
        return try {
            // Optionally compute and attach a source route for addressed packets
            val withRoute = try {
                val rec = packet.recipientID
                if (rec != null && !rec.contentEquals(SpecialRecipients.BROADCAST)) {
                    val dest = rec.hexEncodedString()
                    val path = RoutePlanner.shortestPath(myPeerID, dest, meshGraphService)
                    if (path != null && path.size >= 3) {
                        // Exclude first (sender) and last (recipient); only intermediates
                        val intermediates = path.subList(1, path.size - 1)
                        val hopsBytes = intermediates.map { peerIdToRoutingBytes(it) }
                        Log.d(TAG, "✅ Signed packet type ${packet.type} (route ${hopsBytes.size} hops: $intermediates)")
                        // Attach route and upgrade to v2 (required for HAS_ROUTE flag)
                        packet.copy(route = hopsBytes, version = 2u)
                    } else packet.copy(route = null)
                } else packet
            } catch (_: Exception) { packet }

            // Get the canonical packet data for signing (without signature)
            val packetDataForSigning = withRoute.toBinaryDataForSigning()
            if (packetDataForSigning == null) {
                Log.w(TAG, "Failed to encode packet type ${packet.type} for signing, sending unsigned")
                return withRoute
            }

            // Sign the packet data using our signing key
            val signature = encryptionService.signData(packetDataForSigning)
            if (signature != null) {
                Log.d(TAG, "✅ Signed packet type ${packet.type} (signature ${signature.size} bytes)")
                withRoute.copy(signature = signature)
            } else {
                Log.w(TAG, "Failed to sign packet type ${packet.type}, sending unsigned")
                withRoute
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error signing packet type ${packet.type}: ${e.message}, sending unsigned")
            packet
        }
    }

    // Local helper to hash payloads to a stable hex ID for progress mapping
    private fun sha256Hex(bytes: ByteArray): String = try {
        Sha256.digest(bytes).hexEncodedString()
    } catch (_: Exception) { bytes.size.toString(16) }
}
