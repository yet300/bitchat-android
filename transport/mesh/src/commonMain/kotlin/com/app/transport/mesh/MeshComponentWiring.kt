package com.app.transport.mesh

import com.app.common.utils.Log
import com.app.crypto.EncryptionService
import com.app.transport.FavoriteNostrLink
import com.app.transport.IncomingMessageSink
import com.app.transport.MeshConstants
import com.app.transport.meshgraph.MeshGraphService
import com.app.transport.model.BitchatMessage
import com.app.transport.model.PeerCapabilities
import com.app.transport.model.RequestSyncPacket
import com.app.transport.model.RoutedPacket
import com.app.transport.notification.NotificationTextUtils
import com.app.transport.notification.ServiceNotifier
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.peerIdToRoutingBytes
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.SpecialRecipients
import com.app.transport.sync.GossipSyncManager
import com.app.transport.board.BoardEventListener
import com.app.transport.courier.CourierEventListener
import com.app.transport.group.GroupEventListener
import com.app.transport.model.BoardWire
import com.app.transport.prekey.PrekeyEventListener
import com.app.transport.verification.VerifyEventListener
import com.app.transport.vouch.VouchEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Inter-component delegate wiring of one mesh engine generation. Extracted verbatim from
 * [BluetoothMeshService.setupDelegates]; the coordinator constructs one instance per
 * generation (init and each rebuild) and calls [wire] once. UI delegate and verify listener
 * are read through suppliers because they are mutable on the coordinator.
 */
internal class MeshComponentWiring(
    private val myPeerID: String,
    private val scope: CoroutineScope,
    private val peerManager: PeerManager,
    private val securityManager: SecurityManager,
    private val storeForwardManager: StoreForwardManager,
    private val messageHandler: MessageHandler,
    private val packetProcessor: PacketProcessor,
    private val fragmentManager: FragmentManager,
    private val gossipSyncManager: GossipSyncManager,
    private val meshNetwork: MeshNetwork,
    private val meshGraphService: MeshGraphService,
    private val encryptionService: EncryptionService,
    private val incomingSink: IncomingMessageSink,
    private val serviceNotifier: ServiceNotifier,
    private val favoriteNostrLink: FavoriteNostrLink,
    private val outbound: MeshOutboundSender,
    private val pingService: MeshPingService,
    private val uiDelegate: () -> BluetoothMeshDelegate?,
    private val verifyListener: () -> VerifyEventListener?,
    private val vouchListener: () -> VouchEventListener?,
    private val courierListener: () -> CourierEventListener?,
    private val groupListener: () -> GroupEventListener?,
    private val boardListener: () -> BoardEventListener?,
    private val prekeyListener: () -> PrekeyEventListener?,
    private val nostrCarrierHandler: () -> ((ByteArray, String, Boolean) -> Unit)?,
) {

    companion object {
        private const val TAG = "BluetoothMeshService"
    }

    fun wire() {
        Log.d(TAG, "Setting up component delegates")
        wirePeerManager()
        wireSecurityManager()
        wireStoreForwardManager()
        wireMessageHandler()
        wirePacketProcessor()
        wireEncryptionCallbacks()
    }

    private fun wireEncryptionCallbacks() {
        // Session-established trigger for transitive verification: on a Noise session coming up with a
        // peer (fingerprint resolved), the vouch coordinator may send a batch. Additive — nothing
        // else consumes this callback. Each generation re-registers, so the latest wins.
        encryptionService.onPeerAuthenticated = { peerID, fingerprint ->
            vouchListener()?.onPeerAuthenticated(peerID, fingerprint)
        }
    }

    private fun wirePeerManager() {
        // PeerManager delegates to main mesh service delegate
        peerManager.delegate = object : PeerManagerDelegate {
            override fun onPeerListUpdated(peerIDs: List<String>) {
                // Update process-wide state first
                try { incomingSink.setPeers(peerIDs) } catch (_: Exception) { }
                // Then notify UI delegate if attached
                uiDelegate()?.didUpdatePeerList(peerIDs)
            }
            override fun onPeerRemoved(peerID: String) {
                try { gossipSyncManager.removeAnnouncementForPeer(peerID) } catch (_: Exception) { }
                // Remove from mesh graph topology to prevent routing through stale peers
                try { meshGraphService.removePeer(peerID) } catch (_: Exception) { }

                // Also drop any Noise session state for this peer when they go offline
                try {
                    encryptionService.removePeer(peerID)
                    securityManager.resetNoiseRateLimits(peerID)
                    Log.d(TAG, "Removed Noise session for offline peer $peerID")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove Noise session for $peerID: ${e.message}")
                }
            }
        }
    }

    private fun wireSecurityManager() {
        // SecurityManager delegate for key exchange notifications
        securityManager.delegate = object : SecurityManagerDelegate {
            override fun onKeyExchangeCompleted(peerID: String, peerPublicKeyData: ByteArray) {
                // Send announcement and cached messages after key exchange
                scope.launch {
                    Log.d(TAG, "Key exchange completed with $peerID; sending follow-ups")
                    delay(100)
                    outbound.sendAnnouncementToPeer(peerID)

                    delay(1000)
                    storeForwardManager.sendCachedMessages(peerID)
                }
            }

            override fun sendHandshakeResponse(peerID: String, response: ByteArray) {
                // Send Noise handshake response
                val responsePacket = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_HANDSHAKE.value,
                    senderID = peerIdToRoutingBytes(myPeerID),
                    recipientID = peerIdToRoutingBytes(peerID),
                    timestamp = epochMillis().toULong(),
                    payload = response,
                    ttl = MeshConstants.MESSAGE_TTL_HOPS
                )
                // Sign the handshake response
                val signedPacket = outbound.signPacketBeforeBroadcast(responsePacket)
                meshNetwork.broadcast(RoutedPacket(signedPacket))
                Log.d(TAG, "Sent Noise handshake response to $peerID (${response.size} bytes)")
            }

            override fun getPeerInfo(peerID: String): PeerInfo? {
                return peerManager.getPeerInfo(peerID)
            }
        }
    }

    private fun wireStoreForwardManager() {
        storeForwardManager.delegate = object : StoreForwardManagerDelegate {
            override fun isFavorite(peerID: String): Boolean {
                return uiDelegate()?.isFavorite(peerID) ?: false
            }

            override fun isPeerOnline(peerID: String): Boolean {
                return peerManager.isPeerActive(peerID)
            }

            override fun sendPacket(packet: BitchatPacket) {
                meshNetwork.broadcast(RoutedPacket(packet))
            }
        }
    }

    private fun wireMessageHandler() {
        messageHandler.delegate = object : MessageHandlerDelegate {
            // Peer management
            override fun addOrUpdatePeer(peerID: String, nickname: String): Boolean {
                return peerManager.addOrUpdatePeer(peerID, nickname)
            }

            override fun removePeer(peerID: String) {
                peerManager.removePeer(peerID)
            }

            override fun updatePeerNickname(peerID: String, nickname: String) {
                peerManager.addOrUpdatePeer(peerID, nickname)
            }

            override fun getPeerNickname(peerID: String): String? {
                return peerManager.getPeerNickname(peerID)
            }

            override fun getNetworkSize(): Int {
                return peerManager.getActivePeerCount()
            }

            override fun getMyNickname(): String? {
                return uiDelegate()?.getNickname()
            }

            override fun getPeerInfo(peerID: String): PeerInfo? {
                return peerManager.getPeerInfo(peerID)
            }

            override fun updatePeerInfo(
                peerID: String,
                nickname: String,
                noisePublicKey: ByteArray,
                signingPublicKey: ByteArray,
                isVerified: Boolean,
                capabilities: PeerCapabilities,
            ): Boolean {
                // Persist the announce-bound signing key so a vouch for this identity can still be
                // built (and its own vouches verified) once the peer goes offline.
                if (isVerified) {
                    try {
                        encryptionService.cacheAnnouncedSigningKey(noisePublicKey, signingPublicKey)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to cache announced signing key for ${peerID.take(8)}: ${e.message}")
                    }
                }
                return peerManager.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified, capabilities)
            }

            // Packet operations
            override fun sendPacket(packet: BitchatPacket) {
                // Sign the packet before broadcasting
                val signedPacket = outbound.signPacketBeforeBroadcast(packet)
                meshNetwork.broadcast(RoutedPacket(signedPacket))
            }

            override fun relayPacket(routed: RoutedPacket) {
                meshNetwork.broadcast(routed)
            }

            override fun getBroadcastRecipient(): ByteArray {
                return SpecialRecipients.BROADCAST
            }

            // Cryptographic operations
            override fun verifySignature(packet: BitchatPacket, peerID: String): Boolean {
                return securityManager.verifySignature(packet, peerID)
            }

            override fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray? {
                return securityManager.encryptForPeer(data, recipientPeerID)
            }

            override fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String): ByteArray? {
                return securityManager.decryptFromPeer(encryptedData, senderPeerID)
            }

            override fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean {
                return encryptionService.verifyEd25519Signature(signature, data, publicKey)
            }

            // Noise protocol operations
            override fun hasNoiseSession(peerID: String): Boolean {
                return encryptionService.hasEstablishedSession(peerID)
            }

            override fun initiateNoiseHandshake(peerID: String) {
                try {
                    // Initiate proper Noise handshake with specific peer
                    val handshakeData = encryptionService.initiateHandshake(peerID)

                    if (handshakeData != null) {
                        val packet = BitchatPacket(
                            version = 1u,
                            type = MessageType.NOISE_HANDSHAKE.value,
                            senderID = peerIdToRoutingBytes(myPeerID),
                            recipientID = peerIdToRoutingBytes(peerID),
                            timestamp = epochMillis().toULong(),
                            payload = handshakeData,
                            ttl = MeshConstants.MESSAGE_TTL_HOPS
                        )

                        // Sign the handshake packet before broadcasting
                        val signedPacket = outbound.signPacketBeforeBroadcast(packet)
                        meshNetwork.broadcast(RoutedPacket(signedPacket))
                        Log.d(TAG, "Initiated Noise handshake with $peerID (${handshakeData.size} bytes)")
                    } else {
                        Log.w(TAG, "Failed to generate Noise handshake data for $peerID")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initiate Noise handshake with $peerID: ${e.message}")
                }
            }

            override fun processNoiseHandshakeMessage(payload: ByteArray, peerID: String): ByteArray? {
                return try {
                    encryptionService.processHandshakeMessage(payload, peerID)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process handshake message from $peerID: ${e.message}")
                    null
                }
            }

            override fun updatePeerIDBinding(newPeerID: String, nickname: String,
                                           publicKey: ByteArray, previousPeerID: String?) {

                Log.d(TAG, "Updating peer ID binding: $newPeerID (was: $previousPeerID) with nickname: $nickname")
                // Update peer mapping in the PeerManager for peer ID rotation support
                peerManager.addOrUpdatePeer(newPeerID, nickname)

                // Store fingerprint for the peer via centralized fingerprint manager
                val fingerprint = peerManager.storeFingerprintForPeer(newPeerID, publicKey)

                // Index existing Nostr mapping by the new peerID if we have it
                try {
                    favoriteNostrLink.findNostrPubkey(publicKey)?.let { npub ->
                        favoriteNostrLink.updateNostrPublicKeyForPeerId(newPeerID, npub)
                    }
                } catch (_: Exception) { }

                // If there was a previous peer ID, remove it to avoid duplicates
                previousPeerID?.let { oldPeerID ->
                    peerManager.removePeer(oldPeerID)
                }

                Log.d(TAG, "Updated peer ID binding: $newPeerID (was: $previousPeerID), fingerprint: ${fingerprint.take(16)}...")
            }

            // Message operations
            override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
                return uiDelegate()?.decryptChannelMessage(encryptedContent, channel)
            }

            // Callbacks
            override fun onMessageReceived(message: BitchatMessage) {
                // Always reflect into process-wide store so UI can hydrate after recreation
                try {
                    when {
                        message.isPrivate -> {
                            val peer = message.senderPeerID ?: ""
                            if (peer.isNotEmpty()) incomingSink.addPrivateMessage(peer, message)
                        }
                        message.channel != null -> {
                            // Explicit local: smart cast is unavailable across module boundaries.
                            val channel = message.channel ?: ""
                            incomingSink.addChannelMessage(channel, message)
                        }
                        else -> {
                            incomingSink.addPublicMessage(message)
                        }
                    }
                } catch (_: Exception) { }
                // And forward to UI delegate if attached
                val delegate = uiDelegate()
                delegate?.didReceiveMessage(message)

                // If no UI delegate attached (app closed), show DM notification via service manager
                if (delegate == null && message.isPrivate) {
                    try {
                        val senderPeerID = message.senderPeerID
                        if (senderPeerID != null) {
                            val nick = try { peerManager.getPeerNickname(senderPeerID) } catch (_: Exception) { null } ?: senderPeerID
                            val preview = NotificationTextUtils.buildPrivateMessagePreview(message)
                            serviceNotifier.setAppBackgroundState(true)
                            serviceNotifier.showPrivateMessageNotification(senderPeerID, nick, preview)
                        }
                    } catch (_: Exception) { }
                }
            }

            override fun onChannelLeave(channel: String, fromPeer: String) {
                uiDelegate()?.didReceiveChannelLeave(channel, fromPeer)
            }

            override fun onDeliveryAckReceived(messageID: String, peerID: String) {
                // Advance the persistent status ladder. The top-level delegate is unset under the
                // Decompose UI, so the sink (the process-wide store the UI hydrates from) is the
                // live target — mirrors the deleted MeshDelegateHandler.didReceiveDeliveryAck.
                try { incomingSink.onDeliveryAck(messageID, peerID) } catch (_: Exception) { }
                uiDelegate()?.didReceiveDeliveryAck(messageID, peerID)
            }

            override fun onReadReceiptReceived(messageID: String, peerID: String) {
                try { incomingSink.onReadReceipt(messageID, peerID) } catch (_: Exception) { }
                uiDelegate()?.didReceiveReadReceipt(messageID, peerID)
            }

            override fun onVerifyChallengeReceived(peerID: String, payload: ByteArray, timestampMs: Long) {
                uiDelegate()?.didReceiveVerifyChallenge(peerID, payload, timestampMs)
                verifyListener()?.onVerifyChallenge(peerID, payload, timestampMs)
            }

            override fun onVerifyResponseReceived(peerID: String, payload: ByteArray, timestampMs: Long) {
                uiDelegate()?.didReceiveVerifyResponse(peerID, payload, timestampMs)
                verifyListener()?.onVerifyResponse(peerID, payload, timestampMs)
            }

            override fun onVouchAttestationsReceived(peerID: String, payload: ByteArray, timestampMs: Long) {
                vouchListener()?.onVouchAttestations(peerID, payload, timestampMs)
            }

            // Courier store-and-forward (0x04)
            override fun myNoiseStaticKey(): ByteArray? = encryptionService.getStaticPublicKey()

            override fun openCourierPayload(ciphertext: ByteArray, prekeyID: UInt?): Pair<ByteArray, ByteArray>? {
                // v1 (static-sealed) envelopes open to our identity static key.
                if (prekeyID == null) return encryptionService.openCourierPayload(ciphertext)
                // v2 (prekey-sealed) envelopes open to one of our one-time prekeys, consuming it.
                val opened = encryptionService.openPrekeyPayload(ciphertext, prekeyID) ?: return null
                if (opened.consumedPrekey) {
                    // The published bundle shrank; let the coordinator replenish + re-gossip.
                    prekeyListener()?.onLocalPrekeyConsumed()
                }
                return opened.payload to opened.senderStaticKey
            }

            override fun peerIDForNoiseKey(noiseKey: ByteArray): String? {
                return try {
                    peerManager.getActivePeerIDs().firstOrNull { id ->
                        peerManager.getPeerInfo(id)?.noisePublicKey?.contentEquals(noiseKey) == true
                    }
                } catch (_: Exception) { null }
            }

            override fun onCourierDeposit(fromPeerID: String, packet: BitchatPacket) {
                courierListener()?.onCourierDeposit(fromPeerID, packet)
            }

            override fun onGroupStateReceived(fromPeerID: String, isInvite: Boolean, payload: ByteArray) {
                groupListener()?.onGroupStateReceived(fromPeerID, isInvite, payload)
            }
        }
    }

    private fun wirePacketProcessor() {
        packetProcessor.delegate = object : PacketProcessorDelegate {
            override fun validatePacketSecurity(packet: BitchatPacket, peerID: String): PacketValidationResult {
                return securityManager.validatePacket(packet, peerID)
            }

            override fun handleDuplicateAnnounceLiveness(routed: RoutedPacket) {
                val pid = routed.peerID ?: return
                // Refresh the link→peer binding on the receiving link (a reconnect may have
                // moved the peer to a new address) and bump last-seen. Nothing else: the
                // announce content was already processed on its first delivery.
                val deviceAddress = routed.relayAddress
                if (deviceAddress != null && routed.packet.ttl == MeshConstants.MESSAGE_TTL_HOPS) {
                    meshNetwork.bindPeer(pid, deviceAddress)
                }
                peerManager.updatePeerLastSeen(pid)
            }

            override fun updatePeerLastSeen(peerID: String) {
                peerManager.updatePeerLastSeen(peerID)
            }

            override fun getPeerNickname(peerID: String): String? {
                return peerManager.getPeerNickname(peerID)
            }

            // Network information for relay manager
            override fun getNetworkSize(): Int {
                return peerManager.getActivePeerCount()
            }

            override fun getLocalDegree(): Int {
                // Directly connected links across all bearers — the iOS RelayController
                // degree. Deliberately NOT getActivePeerCount() (total multi-hop peers).
                return try { meshNetwork.allNeighbors.size } catch (_: Exception) { 0 }
            }

            override fun getBroadcastRecipient(): ByteArray {
                return SpecialRecipients.BROADCAST
            }

            override fun handleGroupMessage(routed: RoutedPacket) {
                // Opaque group broadcast: track for gossip backfill, then hand the payload to the group
                // coordinator (which opens + authenticates against the roster). Relay happens on the
                // generic broadcast path in PacketProcessor.
                try { gossipSyncManager.onPublicPacketSeen(routed.packet) } catch (_: Exception) { }
                groupListener()?.onGroupMessageReceived(routed.packet.payload, routed.packet.timestamp.toLong())
            }

            override fun handleBoardPost(routed: RoutedPacket): Boolean {
                // Self-authenticating: decode + verify the inner author Ed25519 signature synchronously
                // so a malformed or forged post is dropped and NOT relayed. Valid posts are tracked for
                // gossip backfill and handed to the board coordinator for (async) ingest.
                val wire = BoardWire.decode(routed.packet.payload) ?: return false
                val verified = wire.verifySignature { key, data, sig ->
                    encryptionService.verifyEd25519Signature(sig, data, key)
                }
                if (!verified) return false
                try { gossipSyncManager.onPublicPacketSeen(routed.packet) } catch (_: Exception) { }
                boardListener()?.onBoardPacketReceived(routed.packet.payload)
                return true
            }

            override fun handleNostrCarrier(routed: RoutedPacket, directedToUs: Boolean) {
                val from = routed.peerID ?: return
                nostrCarrierHandler()?.invoke(routed.packet.payload, from, directedToUs)
            }

            override suspend fun handleNoiseHandshake(routed: RoutedPacket): Boolean {
                return securityManager.handleNoiseHandshake(routed)
            }

            override fun handleNoiseEncrypted(routed: RoutedPacket) {
                scope.launch { messageHandler.handleNoiseEncrypted(routed) }
            }

            override fun handleAnnounce(routed: RoutedPacket) {
                scope.launch {
                    // Process the announce
                    val isFirst = messageHandler.handleAnnounce(routed)

                    // Map device address -> peerID based on TTL (max TTL = direct neighbor)
                    // Matches iOS logic: any announce with max TTL on a link defines the direct peer
                    val deviceAddress = routed.relayAddress
                    val pid = routed.peerID
                    if (deviceAddress != null && pid != null) {
                        // Check if this is a direct connection (MAX TTL)
                        // Note: packet.ttl is UByte, compare with MeshConstants.MESSAGE_TTL_HOPS
                        val isDirect = routed.packet.ttl == MeshConstants.MESSAGE_TTL_HOPS

                        if (isDirect) {
                            // Engine decision: announce with max TTL ⇒ direct neighbor.
                            // The bearer owns the address↔peer map and neighbors state.
                            meshNetwork.bindPeer(pid, deviceAddress)
                            Log.d(TAG, "Mapped device $deviceAddress to peer $pid (TTL=${routed.packet.ttl})")

                            // Mark as directly connected - refresh UI state
                            try { peerManager.refreshPeerList() } catch (_: Exception) { }

                            // Initial sync for this direct peer
                            try { gossipSyncManager.scheduleInitialSyncToPeer(pid, 1_000) } catch (_: Exception) { }
                        }
                    }
                    // Courier handover: a verified announce is the moment we learn a peer's Noise
                    // static key, so hand over any carried mail addressed to them (direct) or push a
                    // speculative copy toward them (relayed). Verified announces only.
                    try {
                        if (pid != null && peerManager.getPeerInfo(pid)?.isVerifiedNickname == true) {
                            courierListener()?.onCourierPeerAvailable(pid)
                        }
                    } catch (_: Exception) { }

                    // Track for sync
                    try { gossipSyncManager.onPublicPacketSeen(routed.packet) } catch (_: Exception) { }
                }
            }

            override fun handleMessage(routed: RoutedPacket) {
                scope.launch { messageHandler.handleMessage(routed) }
                // Track broadcast messages for sync
                try {
                    val pkt = routed.packet
                    val isBroadcast = (pkt.recipientID == null || pkt.recipientID.contentEquals(SpecialRecipients.BROADCAST))
                    if (isBroadcast && pkt.type == MessageType.MESSAGE.value) {
                        gossipSyncManager.onPublicPacketSeen(pkt)
                    }
                } catch (_: Exception) { }
            }

            override fun handleLeave(routed: RoutedPacket) {
                scope.launch { messageHandler.handleLeave(routed) }
            }

            override fun handleFragment(packet: BitchatPacket): BitchatPacket? {
                // Track broadcast fragments for gossip sync
                try {
                    val isBroadcast = (packet.recipientID == null || packet.recipientID.contentEquals(SpecialRecipients.BROADCAST))
                    if (isBroadcast && packet.type == MessageType.FRAGMENT.value) {
                        gossipSyncManager.onPublicPacketSeen(packet)
                    }
                } catch (_: Exception) { }
                return fragmentManager.handleFragment(packet)
            }

            override fun sendAnnouncementToPeer(peerID: String) {
                outbound.sendAnnouncementToPeer(peerID)
            }

            override fun sendCachedMessages(peerID: String) {
                storeForwardManager.sendCachedMessages(peerID)
            }

            override fun relayPacket(routed: RoutedPacket) {
                meshNetwork.broadcast(routed)
            }

            override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean {
                // Direct and Flooded both mean the packet is on the air — the relay must
                // NOT broadcast again (that would double-flood). Only NoRoute lets the
                // relay run its own fallback.
                return meshNetwork.sendToPeer(peerID, routed) != SendPath.NoRoute
            }

            override fun handleRequestSync(routed: RoutedPacket) {
                // Decode request and respond with missing packets
                val fromPeer = routed.peerID ?: return
                val req = RequestSyncPacket.decode(routed.packet.payload) ?: return
                gossipSyncManager.handleRequestSync(fromPeer, req)
            }

            override fun handlePing(routed: RoutedPacket, linkKey: String) {
                pingService.onPingReceived(routed, linkKey)
            }

            override fun handlePong(routed: RoutedPacket) {
                pingService.onPongReceived(routed)
            }

            override fun handleCourierEnvelope(routed: RoutedPacket) {
                scope.launch { messageHandler.handleCourierEnvelope(routed) }
            }

            override fun handlePrekeyBundle(routed: RoutedPacket) {
                // Hand the raw broadcast to the coordinator for attribution + signature verification
                // and caching. The generic relay step runs regardless (bundles must spread even
                // before this node can verify them). Gossip backfill is not tracked here: like board
                // posts in this port, bundles propagate by broadcast + relay, not GCS diff.
                prekeyListener()?.onPrekeyBundleReceived(routed.packet)
            }
        }
    }
}
