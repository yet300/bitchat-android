package com.app.transport.mesh

import com.app.common.AppDispatchers
import com.app.common.encoding.hexEncodedString
import com.app.common.utils.Log
import com.app.transport.MeshTelemetry
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.SpecialRecipients
import com.app.transport.crypto.Sha256
import com.app.transport.model.RoutedPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel

/**
 * Processes incoming packets and routes them to appropriate handlers
 *
 * Per-peer packet serialization using a dedicated channel + consumer coroutine
 * per peer. Prevents the race condition where multiple threads process packets
 * from the same peer simultaneously, causing session management conflicts.
 */
internal class PacketProcessor(
    private val myPeerID: String,
    private val debugSettingsManager: MeshTelemetry,
    dispatchers: AppDispatchers = AppDispatchers(),
    // Public broadcast MESSAGE intake budget (iOS ChatViewModel.publicRateLimiter parity):
    // per-sender + per-content token buckets, checked before store/relay. Mesh has no
    // PoW, so powBits stays 0 and the sender bucket always applies. Injectable for tests.
    private val publicMessageRateLimiter: MessageRateLimiter = MessageRateLimiter(),
) {
    private val debugManager = debugSettingsManager
    
    companion object {
        private const val TAG = "PacketProcessor"

        // Hard bound on the number of per-peer serialization actors. peerID is derived from the
        // attacker-controlled packet.senderID and the actor is created BEFORE signature validation,
        // so an unbounded map would let a peer spoofing random sender IDs create one coroutine per
        // fake ID. Beyond this cap the oldest actor is evicted (its channel closed); a real peer
        // whose actor was evicted simply gets a fresh one on its next packet (the actor holds no
        // per-peer state — it only serializes processing).
        private const val MAX_PEER_ACTORS = 256

        // Per-peer serialization channel capacity. Bounded (was UNLIMITED) as a second anti-DoS
        // bound alongside MAX_PEER_ACTORS: peerID is derived from the attacker-controlled senderID
        // before signature validation, so a single flooding peer must not be able to grow one
        // actor's queue without limit. On overflow the newest frame is dropped with a log (never
        // reordered), which the mesh dedup/relay layer tolerates far better than lost ordering.
        private const val ACTOR_CHANNEL_CAPACITY = 128
    }
    
    // Delegate for callbacks
    var delegate: PacketProcessorDelegate? = null
    
    // Helper function to format peer ID with nickname for logging
    private fun formatPeerForLog(peerID: String): String {
        val nickname = delegate?.getPeerNickname(peerID)
        return if (nickname != null) "$peerID ($nickname)" else peerID
    }
    
    // Packet relay manager for centralized relay decisions
    private val packetRelayManager = PacketRelayManager(myPeerID, debugSettingsManager, dispatchers)

    // Coroutines
    private val processorScope = CoroutineScope(dispatchers.io + SupervisorJob())

    // Per-peer serialization: each peer gets its own unbounded channel drained by a
    // dedicated consumer coroutine, so packets from one peer are processed sequentially.
    // This prevents race conditions in session management. (Replaces the obsolete
    // coroutine `actor` builder, which is JVM-only, so this can live in commonMain.)
    private fun getOrCreateActorForPeer(peerID: String): SendChannel<RoutedPacket> {
        val channel = Channel<RoutedPacket>(ACTOR_CHANNEL_CAPACITY)
        processorScope.launch {
            Log.d(TAG, "🎭 Created packet actor for peer: ${formatPeerForLog(peerID)}")
            try {
                for (packet in channel) {
                    Log.d(TAG, "📦 Processing packet type ${packet.packet.type} from ${formatPeerForLog(peerID)} (serialized)")
                    handleReceivedPacket(packet)
                    Log.d(TAG, "Completed packet type ${packet.packet.type} from ${formatPeerForLog(peerID)}")
                }
            } finally {
                Log.d(TAG, "🎭 Packet actor for ${formatPeerForLog(peerID)} terminated")
            }
        }
        return channel
    }

    // Cache actors to reuse them
    private val actors = mutableMapOf<String, SendChannel<RoutedPacket>>()
    
    init {
        // Set up the packet relay manager delegate immediately
        setupRelayManager()
    }
    
    /**
     * Process received packet - main entry point for all incoming packets
     * SURGICAL FIX: Route to per-peer actor for serialized processing
     */
    fun processPacket(routed: RoutedPacket) {
        Log.d(TAG, "processPacket ${routed.packet.type}")
        val peerID = routed.peerID

        if (peerID == null) {
            Log.w(TAG, "Received packet with no peer ID, skipping")
            return
        }
        
        // Get or create actor for this peer, evicting the oldest if we exceed the cap. Called from
        // the single MeshNetwork.incoming collector, so no locking is needed here.
        val actor = actors.getOrPut(peerID) { getOrCreateActorForPeer(peerID) }
        if (actors.size > MAX_PEER_ACTORS) {
            val eldest = actors.keys.firstOrNull { it != peerID }
            if (eldest != null) {
                actors.remove(eldest)?.close()
                Log.d(TAG, "Evicted oldest peer actor $eldest (actor cap $MAX_PEER_ACTORS reached)")
            }
        }

        // Enqueue on the peer's serialization channel directly — no launch. processPacket is
        // invoked only from the single MeshNetwork.incoming collector, so a direct trySend
        // preserves strict per-peer arrival order. The old `launch { actor.send(...) }` spawned
        // one coroutine per packet on a multi-thread dispatcher, so two packets from the same
        // peer could reach the channel out of order — fatal for Noise, where a reordered
        // handshake message forces a re-handshake. trySend never suspends (bounded channel) and
        // returns a result we can act on without a coroutine.
        val result = actor.trySend(routed)
        if (result.isFailure) {
            // Full or closed: drop the newest frame with a log. We deliberately do NOT fall back
            // to direct handleReceivedPacket() — that bypassed the per-peer actor and reintroduced
            // the very out-of-order processing the actors exist to prevent.
            val reason = if (result.isClosed) "closed" else "full (cap $ACTOR_CHANNEL_CAPACITY)"
            Log.w(TAG, "Dropped packet for ${formatPeerForLog(peerID)}: actor channel $reason")
        }
    }
    
    /**
     * Set up the packet relay manager with its delegate
     */
    fun setupRelayManager() {
        packetRelayManager.delegate = object : PacketRelayManagerDelegate {
            override fun getNetworkSize(): Int {
                return delegate?.getNetworkSize() ?: 1
            }

            override fun getLocalDegree(): Int {
                return delegate?.getLocalDegree() ?: 0
            }

            override fun getBroadcastRecipient(): ByteArray {
                return delegate?.getBroadcastRecipient() ?: ByteArray(0)
            }
            
            override fun broadcastPacket(routed: RoutedPacket) {
                delegate?.relayPacket(routed)
            }
            override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean {
                return delegate?.sendToPeer(peerID, routed) ?: false
            }
        }
    }
    
    /**
     * Handle received packet - core protocol logic (exact same as iOS)
     */
    private suspend fun handleReceivedPacket(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        // Basic validation and security checks. A null delegate (teardown/rebuild window)
        // drops the packet — never NPE inside the per-peer consumer coroutine, which would
        // permanently kill that peer's actor.
        when (delegate?.validatePacketSecurity(packet, peerID)) {
            PacketValidationResult.ACCEPT -> Unit
            PacketValidationResult.DUPLICATE -> {
                // Another neighbor has already supplied this packet. If our degree is high
                // enough, cancel the jittered flood that has not left this node yet.
                packetRelayManager.cancelScheduledRelayForDuplicate(packet)
                return
            }
            PacketValidationResult.DUPLICATE_ANNOUNCE_LIVENESS -> {
                // Byte-identical ANNOUNCE from a direct neighbor: refresh link binding and
                // last-seen only. No reprocessing, no relay, no sync scheduling — a peer
                // resending its cached announce (gossip sync) must not amplify.
                delegate?.handleDuplicateAnnounceLiveness(routed)
                return
            }
            PacketValidationResult.DROP, null -> {
                Log.d(TAG, "Packet failed security validation from ${formatPeerForLog(peerID)}")
                return
            }
        }

        var validPacket = true
        val messageType = MessageType.fromValue(packet.type)
        Log.d(TAG, "Processing packet type $messageType from ${formatPeerForLog(peerID)}")
        // Verbose logging to debug manager (and chat via ChatViewModel observer)
        try {
            val mt = messageType?.name ?: packet.type.toString()
            val routeDevice = routed.relayAddress
            val nick = delegate?.getPeerNickname(peerID)
            debugManager.logIncomingPacket(peerID, nick, mt, routeDevice)
        } catch (_: Exception) { }
        
        
        // Handle public packet types (no address check needed)
        when (messageType) {
            MessageType.ANNOUNCE -> handleAnnounce(routed)
            MessageType.MESSAGE -> {
                // Public broadcast MESSAGE intake budget, BEFORE store and relay: a
                // flooding sender (or a replayed content burst) is dropped here and
                // never amplified. Directed messages are not gated (iOS parity: the
                // limiter guards the public intake).
                if (isBroadcast(packet) && !allowPublicMessage(routed, peerID)) return
                handleMessage(routed)
            }
            MessageType.FILE_TRANSFER -> handleMessage(routed) // treat same routing path; parsing happens in handler
            MessageType.LEAVE -> handleLeave(routed)
            MessageType.FRAGMENT -> handleFragment(routed)
            MessageType.REQUEST_SYNC -> handleRequestSync(routed)
            MessageType.GROUP_MESSAGE -> handleGroupMessage(routed)
            MessageType.PREKEY_BUNDLE -> {
                // Verify-then-cache happens in the delegate/coordinator (attribution + inner+outer
                // signatures against the owner's announce-bound signing key). Relay is NOT gated on
                // it: bundles race the announces that bind their signing keys, so a node that cannot
                // verify one yet must still spread it. Falls through to the generic relay step.
                delegate?.handlePrekeyBundle(routed)
            }
            MessageType.BOARD_POST -> {
                // Malformed or forged posts must not spread: the delegate decodes + verifies the inner
                // author signature synchronously and returns false to skip the relay step below.
                if (delegate?.handleBoardPost(routed) != true) return
            }
            MessageType.VOICE_FRAME -> {
                // Public live audio is ephemeral: the delegate synchronously checks the broadcast
                // shape, freshness, payload and outer signature before this falls through to relay.
                if (delegate?.handleVoiceFrame(routed) != true) return
            }
            MessageType.NOSTR_CARRIER -> {
                val directed = packet.recipientID != null && !isBroadcast(packet)
                if (!directed || packetRelayManager.isPacketAddressedToMe(packet)) {
                    delegate?.handleNostrCarrier(routed, directed)
                }
            }
            else -> {
                // Handle private packet types (address check required)
                if (packetRelayManager.isPacketAddressedToMe(packet)) {
                    when (messageType) {
                        MessageType.NOISE_HANDSHAKE -> handleNoiseHandshake(routed)
                        MessageType.NOISE_ENCRYPTED -> handleNoiseEncrypted(routed)
                        MessageType.FILE_TRANSFER -> handleMessage(routed)
                        // Diagnostics echo. The inbound ping budget keys on the ingress LINK
                        // (relayAddress), not on peerID: pings are unsigned, so the claimed sender
                        // is attacker-controlled and rotating it would reset the budget.
                        MessageType.PING -> delegate?.handlePing(routed, routed.relayAddress ?: peerID)
                        MessageType.PONG -> delegate?.handlePong(routed)
                        MessageType.COURIER_ENVELOPE -> delegate?.handleCourierEnvelope(routed)
                        else -> {
                            validPacket = false
                            Log.w(TAG, "Unknown message type: ${packet.type}")
                        }
                    }
                } else {
                    Log.d(TAG, "Private packet type $messageType not addressed to us (from: ${formatPeerForLog(peerID)} to ${packet.recipientID?.hexEncodedString()}), skipping")
                }
            }
        }
        
        // Update last seen timestamp
        if (validPacket) {
            delegate?.updatePeerLastSeen(peerID)
            
            // CENTRALIZED RELAY LOGIC: Handle relay decisions for all packets not addressed to us
            packetRelayManager.handlePacketRelay(routed)
        }
    }
    
    private fun isBroadcast(packet: BitchatPacket): Boolean {
        val recipientID = packet.recipientID ?: return true
        return recipientID.contentEquals(SpecialRecipients.BROADCAST)
    }

    /** Both buckets must pass; drop is logged + counted, never relayed. */
    private fun allowPublicMessage(routed: RoutedPacket, peerID: String): Boolean {
        val contentKey = try {
            Sha256.digest(routed.packet.payload).copyOf(8).hexEncodedString()
        } catch (_: Exception) {
            routed.packet.payload.size.toString()
        }
        if (publicMessageRateLimiter.allow(senderKey = peerID, contentKey = contentKey)) return true
        Log.w(TAG, "Rate-limited public MESSAGE from ${formatPeerForLog(peerID)}")
        try { debugManager.onRateLimitDrop("publicMessage") } catch (_: Exception) { }
        return false
    }

    /**
     * Handle Noise handshake message - SIMPLIFIED iOS-compatible version
     */
    private suspend fun handleNoiseHandshake(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.d(TAG, "Processing Noise handshake from ${formatPeerForLog(peerID)}")
        delegate?.handleNoiseHandshake(routed)
    }
    
    /**
     * Handle Noise encrypted transport message
     */
    private fun handleNoiseEncrypted(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.d(TAG, "Processing Noise encrypted message from ${formatPeerForLog(peerID)}")
        delegate?.handleNoiseEncrypted(routed)
    }
    
    /**
     * Handle announce message
     */
    private fun handleAnnounce(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.d(TAG, "Processing announce from ${formatPeerForLog(peerID)}")
        delegate?.handleAnnounce(routed)
    }
    
    /**
     * Handle regular message
     */
    private fun handleMessage(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.d(TAG, "Processing message from ${formatPeerForLog(peerID)}")
        delegate?.handleMessage(routed)
    }
    
    /**
     * Handle a group message broadcast (0x25). Opaque ciphertext here — the delegate tracks it for
     * gossip backfill and hands the payload to the group coordinator, which opens and authenticates
     * it against the roster. Non-members still relay via the generic broadcast path below.
     */
    private fun handleGroupMessage(routed: RoutedPacket) {
        delegate?.handleGroupMessage(routed)
    }

    /**
     * Handle leave message
     */
    private fun handleLeave(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.d(TAG, "Processing leave from ${formatPeerForLog(peerID)}")
        delegate?.handleLeave(routed)
    }
    
    /**
     * Handle message fragments
     */
    private suspend fun handleFragment(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.d(TAG, "Processing fragment from ${formatPeerForLog(peerID)}")
        
        val reassembledPacket = delegate?.handleFragment(routed.packet)
        if (reassembledPacket != null) {
            Log.d(TAG, "Fragment reassembled, processing complete message")
            handleReceivedPacket(RoutedPacket(reassembledPacket, routed.peerID, routed.relayAddress))
        }
        
        // Fragment relay is now handled by centralized PacketRelayManager
    }

    /**
     * Handle REQUEST_SYNC packets (public, TTL=1)
     */
    private fun handleRequestSync(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.d(TAG, "Processing REQUEST_SYNC from ${formatPeerForLog(peerID)}")
        delegate?.handleRequestSync(routed)
    }
    
    /**
     * Handle delivery acknowledgment
     */
//    private suspend fun handleDeliveryAck(routed: RoutedPacket) {
//        val peerID = routed.peerID ?: "unknown"
//        Log.d(TAG, "Processing delivery ACK from ${formatPeerForLog(peerID)}")
//        delegate?.handleDeliveryAck(routed)
//    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Packet Processor Debug Info ===")
            appendLine("Processor Scope Active: ${processorScope.isActive}")
            appendLine("Active Peer Actors: ${actors.size}")
            appendLine("My Peer ID: $myPeerID")
            
            if (actors.isNotEmpty()) {
                appendLine("Peer Actors:")
                actors.keys.forEach { peerID ->
                    appendLine("  - $peerID")
                }
            }
        }
    }
    
    /**
     * Shutdown the processor and all peer actors
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down PacketProcessor and ${actors.size} peer actors")
        
        // Close all peer actors gracefully
        actors.values.forEach { actor ->
            actor.close()
        }
        actors.clear()
        
        // Shutdown the relay manager
        packetRelayManager.shutdown()
        
        // Cancel the main scope
        processorScope.cancel()
        
        Log.d(TAG, "PacketProcessor shutdown complete")
    }
}

/**
 * Delegate interface for packet processor callbacks
 */
internal interface PacketProcessorDelegate {
    // Security validation
    fun validatePacketSecurity(packet: BitchatPacket, peerID: String): PacketValidationResult

    // Liveness-only path for duplicate direct-neighbor announces: refresh the
    // link→peer binding and last-seen without reprocessing, relaying or sync scheduling.
    fun handleDuplicateAnnounceLiveness(routed: RoutedPacket)

    // Peer management
    fun updatePeerLastSeen(peerID: String)
    fun getPeerNickname(peerID: String): String?
    
    // Network information
    fun getNetworkSize(): Int

    /** LOCAL degree = directly connected links; the relay TTL clamp keys on this. */
    fun getLocalDegree(): Int
    fun getBroadcastRecipient(): ByteArray
    
    // Message type handlers
    // Suspend: runs the (crypto-heavy) handshake inside the per-peer consumer coroutine
    // instead of forcing implementers to block a pooled dispatcher thread via runBlocking.
    suspend fun handleNoiseHandshake(routed: RoutedPacket): Boolean
    fun handleNoiseEncrypted(routed: RoutedPacket)
    fun handleAnnounce(routed: RoutedPacket)
    fun handleMessage(routed: RoutedPacket)
    fun handleLeave(routed: RoutedPacket)
    fun handleFragment(packet: BitchatPacket): BitchatPacket?
    fun handleRequestSync(routed: RoutedPacket)

    /** Directed echo request addressed to us. [linkKey] identifies the ingress link, not the sender. */
    fun handlePing(routed: RoutedPacket, linkKey: String)

    /** Directed echo reply addressed to us; resolves an outstanding local probe. */
    fun handlePong(routed: RoutedPacket)

    /** Directed courier envelope (0x04) addressed to us: open it (we're the recipient) or carry it. */
    fun handleCourierEnvelope(routed: RoutedPacket)

    /** Group message broadcast (0x25): track for gossip backfill and hand up to the group coordinator. */
    fun handleGroupMessage(routed: RoutedPacket)

    /**
     * Prekey bundle broadcast (0x24): hand up to the prekey coordinator to verify + cache. Relay is
     * ungated (returns nothing) — unlike board posts, an unverifiable bundle is still relayed so it
     * can reach nodes that will bind its owner's signing key later.
     */
    fun handlePrekeyBundle(routed: RoutedPacket)

    /**
     * Board post/tombstone broadcast (0x23): decode + verify the inner author signature, track for
     * gossip backfill, and hand up for ingest. Returns whether the packet is worth relaying (false on
     * malformed / bad-signature so forged posts do not spread).
     */
    fun handleBoardPost(routed: RoutedPacket): Boolean

    /** Public live voice (0x29): returns true only after its synchronous relay eligibility gate. */
    fun handleVoiceFrame(routed: RoutedPacket): Boolean

    fun handleNostrCarrier(routed: RoutedPacket, directedToUs: Boolean) = Unit

    // Communication
    fun sendAnnouncementToPeer(peerID: String)
    fun sendCachedMessages(peerID: String)
    fun relayPacket(routed: RoutedPacket)
    fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean
}
