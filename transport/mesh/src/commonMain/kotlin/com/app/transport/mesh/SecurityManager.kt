@file:OptIn(ExperimentalTime::class)

package com.app.transport.mesh

import com.app.common.AppDispatchers
import com.app.common.utils.Log
import com.app.crypto.EncryptionService
import com.app.transport.model.IdentityAnnouncement
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.model.RoutedPacket
import com.app.common.encoding.hexEncodedString
import com.app.common.encoding.toHexString
import com.app.transport.MeshConstants
import com.app.transport.MeshTrafficLog
import com.app.transport.crypto.Sha256
import co.touchlab.stately.collections.ConcurrentMutableMap
import co.touchlab.stately.collections.ConcurrentMutableSet
import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import kotlinx.coroutines.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

/**
 * Outcome of inbound packet validation.
 *
 * [DUPLICATE_ANNOUNCE_LIVENESS] is the narrow exception for a byte-identical ANNOUNCE
 * re-received at max TTL from a direct neighbor: the packet must NOT be reprocessed,
 * relayed or trigger sync scheduling (iOS drops such duplicates outright), but the
 * link→peer binding may still be refreshed so a reconnect on a new link binds correctly.
 */
internal enum class PacketValidationResult {
    ACCEPT,
    DUPLICATE,
    DUPLICATE_ANNOUNCE_LIVENESS,
    DROP,
}

/**
 * Manages security aspects of the mesh network including duplicate detection,
 * replay attack protection, and key exchange handling
 * Extracted from BluetoothMeshService for better separation of concerns
 */
internal class SecurityManager(
    private val encryptionService: EncryptionService,
    private val myPeerID: String,
    private val trafficLog: MeshTrafficLog? = null,
    dispatchers: AppDispatchers = AppDispatchers(),
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val isValidSyncResponse: (peerID: String) -> Boolean = { false },
) {
    
    companion object {
        private const val TAG = "SecurityManager"
        private const val MESSAGE_TIMEOUT = MeshConstants.Security.MESSAGE_TIMEOUT_MS // 5 minutes (same as iOS)
        private const val CLEANUP_INTERVAL = MeshConstants.Security.CLEANUP_INTERVAL_MS // 5 minutes
        private const val MAX_PROCESSED_MESSAGES = MeshConstants.Security.MAX_PROCESSED_MESSAGES
        private const val MAX_PROCESSED_KEY_EXCHANGES = MeshConstants.Security.MAX_PROCESSED_KEY_EXCHANGES
    }
    
    // Security tracking.
    // processedMessages is an insertion-ordered LRU guarded by [processedLock]: the size cap is
    // now enforced at every insertion (evict eldest), not only in the 5-minute cleanup pass, so
    // the set can no longer grow unbounded between cleanups under a packet storm. All access must
    // hold processedLock (a plain LinkedHashSet is not thread-safe and validatePacket runs from
    // multiple per-peer actor coroutines).
    private val processedMessages = LinkedHashSet<String>()
    private val processedLock = Lock()
    private val processedKeyExchanges = ConcurrentMutableSet<String>()
    private val messageTimestamps = ConcurrentMutableMap<String, Long>()

    /** True if [messageID] was already recorded. */
    private fun isProcessed(messageID: String): Boolean =
        processedLock.withLock { processedMessages.contains(messageID) }

    /**
     * Record [messageID] as processed, enforcing [MAX_PROCESSED_MESSAGES] with LRU eviction of the
     * eldest entries at insertion time. Keeps [messageTimestamps] in sync with evictions.
     */
    private fun recordProcessedMessage(messageID: String): Unit = processedLock.withLock {
        if (!processedMessages.add(messageID)) return@withLock
        while (processedMessages.size > MAX_PROCESSED_MESSAGES) {
            val iterator = processedMessages.iterator()
            val eldest = iterator.next()
            iterator.remove()
            messageTimestamps.remove(eldest)
        }
    }

    // Noise DoS budgets (iOS NoiseEncryptionService.allowHandshake/allowMessage parity):
    // per-peer + global sliding windows over the handshake intake and the decrypt path.
    private val noiseRateLimiter = NoiseRateLimiter(nowMillis)

    // Delegate for callbacks
    var delegate: SecurityManagerDelegate? = null

    // Coroutines
    private val managerScope = CoroutineScope(dispatchers.io + SupervisorJob())
    
    init {
        startPeriodicCleanup()
    }
    
    /**
     * Validate packet security (timestamp, replay attacks, duplicates, signatures).
     *
     * @param peerID Logical author ([RoutedPacket.peerID] / packet.senderID) — used for
     *   signature verification and non-RSR clock-skew attribution.
     * @param previousHopPeerID Previous radio hop when known. For RSR, solicitation is
     *   checked against this hop (the peer we asked for sync), not the author of a
     *   multi-hop replayed packet.
     */
    fun validatePacket(
        packet: BitchatPacket,
        peerID: String,
        previousHopPeerID: String? = null,
    ): PacketValidationResult {
        // Ordinary self-loopback is dropped. Exception: solicited self-authored RSR
        // (isRSR && ttl==0) after relaunch — a neighbor returns our public history
        // (iOS isSelfAuthoredSyncResponse / BLEPublicMessagePolicy ttl==0 self).
        val isSelfAuthoredRsr = BleIngressLinkRegistry.isSelfAuthoredSyncResponse(
            packet, peerID, myPeerID,
        )
        if (peerID == myPeerID && !isSelfAuthoredRsr) {
            Log.d(TAG, "Skipping validation for our own packet")
            return PacketValidationResult.DROP
        }

        // Replay attack protection — first-line clock skew matches iOS BLEIngressPacketGuard (120s).
        // Use [nowMillis] (injectable) so unit tests can freeze time.
        val currentTime = nowMillis()
        val messageType = MessageType.fromValue(packet.type)

        // RSR: skip clock skew; solicitation uses the hop we requested sync from
        // (previousHopPeerID), not the logical author of the stored packet.
        // Non-RSR: 120s window attributed to the author peerID.
        val payloadPeerID = if (packet.isRSR) {
            previousHopPeerID ?: peerID
        } else {
            peerID
        }
        BleIngressPacketGuard.validatePayload(
            packet = packet,
            peerID = payloadPeerID,
            nowMs = currentTime,
            maxTimestampSkewMs = BleIngressPacketGuard.DEFAULT_MAX_TIMESTAMP_SKEW_MS,
            isRSR = packet.isRSR,
            isValidSyncResponse = isValidSyncResponse,
        )?.let { rejection ->
            when (rejection) {
                is BleIngressPacketGuard.Rejection.TimestampSkew -> {
                    Log.w(
                        TAG,
                        "Packet timestamp skewed by ${rejection.skewMs}ms " +
                            "(max ${rejection.maxSkewMs}ms) from ${payloadPeerID.take(8)}…",
                    )
                    return PacketValidationResult.DROP
                }
                is BleIngressPacketGuard.Rejection.InvalidRSR -> {
                    Log.w(TAG, "Invalid or unsolicited RSR from ${payloadPeerID.take(8)}…")
                    return PacketValidationResult.DROP
                }
                else -> Unit
            }
        }

        // Duplicate detection. Native BLEReceivePipeline skips dedup for ttl==0 self
        // sync replay so history can re-enter after relaunch.
        val messageID = generateMessageID(packet)

        if (isProcessed(messageID) && !isSelfAuthoredRsr) {
            // ANNOUNCE exception: a byte-identical announce re-received at max TTL still
            // proves the direct link is alive (e.g. after a reconnect on a new link), so
            // report it as liveness-only. It must never be reprocessed or relayed again —
            // gossip-sync peers resend cached announces verbatim, and treating those as
            // fresh amplified every resend into a full announce+relay+sync cycle.
            val isDirectAnnounce = messageType == MessageType.ANNOUNCE &&
                    packet.ttl >= MeshConstants.MESSAGE_TTL_HOPS

            if (!isDirectAnnounce) {
                Log.d(TAG, "Duplicate packet: $messageID")
                return PacketValidationResult.DUPLICATE
            }
            // Signature must still hold before the duplicate may refresh liveness:
            // the messageID is recorded before signature verification, so a replayed
            // forgery would otherwise slip through on its second delivery.
            if (!verifyPacketSignature(packet, peerID)) {
                Log.w(TAG, "Dropping duplicate ANNOUNCE from $peerID: signature verification failed")
                return PacketValidationResult.DROP
            }
            Log.d(TAG, "Duplicate ANNOUNCE from direct neighbor (liveness only): $messageID")
            return PacketValidationResult.DUPLICATE_ANNOUNCE_LIVENESS
        }

        // Add to processed messages (cap enforced at insertion — see [recordProcessedMessage])
        if (!isSelfAuthoredRsr) {
            recordProcessedMessage(messageID)
            messageTimestamps[messageID] = currentTime
        }

        // Enforce mandatory signature verification (own Ed25519 key for self-authored RSR).
        if (!verifyPacketSignature(packet, peerID)) {
            Log.w(TAG, "Dropping packet from $peerID due to signature verification failure")
            return PacketValidationResult.DROP
        }

        Log.d(TAG, "Packet validation passed for $peerID, messageID: $messageID")
        return PacketValidationResult.ACCEPT
    }
    
    /**
     * Handle Noise handshake packet - SIMPLIFIED iOS-compatible version
     * Single handshake type with automatic response handling
     */
    fun handleNoiseHandshake(routed: RoutedPacket): Boolean {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        // Skip handshakes not addressed to us
        if (packet.recipientID?.toHexString() != myPeerID) {
            Log.d(TAG, "Skipping handshake not addressed to us: $peerID")
            return false
        }
            
        // Skip our own handshake messages
        if (peerID == myPeerID) return false

        // Rate limit BEFORE any session mutation or DH work (iOS: allowHandshake before
        // processing the inbound handshake) — a handshake flood must not be able to force
        // session drops or burn CPU. Per-peer 10/min, global 30/min.
        if (!noiseRateLimiter.allowHandshake(peerID)) {
            Log.w(TAG, "Rate-limited Noise handshake from $peerID")
            trafficLog?.onRateLimitDrop("noiseHandshake")
            return false
        }

        // If we already have an established session but the peer is initiating a new handshake,
        // drop the existing session so we can re-establish cleanly.
        var forcedRehandshake = false
        if (encryptionService.hasEstablishedSession(peerID)) {
            Log.d(TAG, "Received new Noise handshake from $peerID with an existing session. Dropping old session to re-handshake.")
            try {
                encryptionService.removePeer(peerID)
                forcedRehandshake = true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove existing Noise session for $peerID: ${e.message}")
            }
        }
        
        if (packet.payload.isEmpty()) {
            Log.w(TAG, "Noise handshake packet has empty payload")
            return false
        }
        
        // Prevent duplicate handshake processing. Collision-resistant digest so distinct
        // handshake messages can never be forged to collide (local-only key, not on the wire).
        val exchangeKey = "$peerID-${Sha256.digest(packet.payload).copyOf(4).hexEncodedString()}"
        
        if (!forcedRehandshake && processedKeyExchanges.contains(exchangeKey)) {
            Log.d(TAG, "Already processed handshake: $exchangeKey")
            return false
        }
        Log.d(TAG, "Processing Noise handshake from $peerID (${packet.payload.size} bytes)")
        processedKeyExchanges.add(exchangeKey)
        
        try {
            // Process the Noise handshake through the updated EncryptionService
            val response = encryptionService.processHandshakeMessage(packet.payload, peerID)
            
            if (response != null) {
                Log.d(TAG, "Successfully processed Noise handshake from $peerID, sending response")
                // Send handshake response through delegate
                delegate?.sendHandshakeResponse(peerID, response)
            }
            // Check if session is now established (handshake complete)
            if (encryptionService.hasEstablishedSession(peerID)) {
                Log.d(TAG, "✅ Noise handshake completed with $peerID")
                delegate?.onKeyExchangeCompleted(peerID, packet.payload)
            }
            return true

            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process Noise handshake from $peerID: ${e.message}")
            return false
        }
    }

    /**
     * Verify packet signature
     */
    fun verifySignature(packet: BitchatPacket, peerID: String): Boolean {
        return packet.signature?.let { signature ->
            try {
                val isValid = encryptionService.verify(signature, packet.payload, peerID)
                if (!isValid) {
                    Log.w(TAG, "Invalid signature for packet from $peerID")
                }
                isValid
            } catch (e: Exception) {
                Log.e(TAG, "Failed to verify signature from $peerID: ${e.message}")
                false
            }
        } ?: true // No signature means verification passes
    }
    
    /**
     * Sign packet payload
     */
    fun signPacket(payload: ByteArray): ByteArray? {
        return try {
            encryptionService.sign(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign packet: ${e.message}")
            null
        }
    }
    
    /**
     * Encrypt payload for specific peer
     */
    fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray? {
        return try {
            encryptionService.encrypt(data, recipientPeerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt for $recipientPeerID: ${e.message}")
            null
        }
    }
    
    /**
     * Decrypt payload from specific peer
     */
    fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String): ByteArray? {
        // Rate limit BEFORE the decrypt work (iOS: allowMessage before decrypt).
        // Per-peer 100/s, global 500/s.
        if (!noiseRateLimiter.allowMessage(senderPeerID)) {
            Log.w(TAG, "Rate-limited Noise message from $senderPeerID")
            trafficLog?.onRateLimitDrop("noiseMessage")
            return null
        }
        return try {
            encryptionService.decrypt(encryptedData, senderPeerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt from $senderPeerID: ${e.message}")
            null
        }
    }
    
    /**
     * Get combined public key data for key exchange
     */
    fun getCombinedPublicKeyData(): ByteArray {
        return encryptionService.getCombinedPublicKeyData()
    }
    
    /**
     * Generate message ID for duplicate detection.
     * Mirrors iOS BLEReceivePipeline.context: sender-timestamp-type-sha256(payload).prefix(4).
     * The SHA-256 digest (vs. the previous 31-based contentHashCode over the first 64 bytes)
     * makes the key collision-resistant so distinct packets cannot be forged to collide,
     * and back-to-back packets sharing sender/timestamp/type are never collapsed.
     * Local-only key — never serialized to the wire.
     */
    private fun generateMessageID(packet: BitchatPacket): String =
        BleIngressLinkRegistry.messageId(packet)
    
    /**
     * Verify packet signature using peer's signing public key
     * Returns true only if signature is present and valid
     */
    private fun verifyPacketSignature(packet: BitchatPacket, peerID: String): Boolean {
        try {
            // only verify ANNOUNCE, MESSAGE, and FILE_TRANSFER
            if (MessageType.fromValue(packet.type) !in setOf(
                    MessageType.ANNOUNCE,
                    MessageType.MESSAGE,
                    MessageType.FILE_TRANSFER
                )) {
                return true
            }
            // 1. Mandatory Signature Check
            if (packet.signature == null) {
                Log.w(TAG, "❌ Signature check for $peerID: NO_SIGNATURE (packet type ${packet.type})")
                return false
            }
            
            // 2. Get Signing Public Key
            var signingPublicKey: ByteArray? = null
            
            if (MessageType.fromValue(packet.type) == MessageType.ANNOUNCE) {
                // Special Case: ANNOUNCE packets carry their own signing key
                try {
                    val announcement = IdentityAnnouncement.decode(packet.payload)
                    signingPublicKey = announcement?.signingPublicKey
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decode announcement for key extraction: ${e.message}")
                }
            } else if (peerID == myPeerID) {
                // Self-authored solicited RSR: verify with our own signing key
                // (we are not in the peer registry as a remote peer).
                signingPublicKey = encryptionService.getSigningPublicKey()
            } else {
                // Standard Case: Get key from known peer info
                val peerInfo = delegate?.getPeerInfo(peerID)
                signingPublicKey = peerInfo?.signingPublicKey
            }
            
            if (signingPublicKey == null) {
                // If we don't have a key (and it's not an announce), we can't verify.
                // For security, we must reject packets from unknown peers unless it's an announce.
                Log.w(TAG, "❌ Signature check for $peerID: NO_SIGNING_KEY_AVAILABLE (packet type ${packet.type})")
                return false
            }
            
            // 3. Get Canonical Data
            val packetDataForSigning = packet.toBinaryDataForSigning()
            if (packetDataForSigning == null) {
                Log.w(TAG, "❌ Signature check for $peerID: ENCODING_ERROR (packet type ${packet.type})")
                return false
            }
            
            // 4. Verify Signature
            val signature = packet.signature!!
            val isSignatureValid = encryptionService.verifyEd25519Signature(
                signature,
                packetDataForSigning,
                signingPublicKey
            )
            
            if (isSignatureValid) {
                // Log.v(TAG, "✅ Signature verified for $peerID (type ${packet.type})")
                return true
            } else {
                Log.w(TAG, "❌ Signature INVALID for $peerID (type ${packet.type})")
                return false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Signature verification error for $peerID: ${e.message}")
            return false
        }
    }
    
    /** Forget a peer's Noise budgets when its session is dropped (iOS reset(for:)). */
    fun resetNoiseRateLimits(peerID: String) = noiseRateLimiter.reset(peerID)

    /**
     * Check if we have encryption keys for a peer
     */
    fun hasKeysForPeer(peerID: String): Boolean {
        return encryptionService.hasEstablishedSession(peerID)
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Security Manager Debug Info ===")
            appendLine("Processed Messages: ${processedLock.withLock { processedMessages.size }}")
            appendLine("Processed Key Exchanges: ${processedKeyExchanges.size}")
            appendLine("Message Timestamps: ${messageTimestamps.size}")
            
            if (processedKeyExchanges.isNotEmpty()) {
                appendLine("Key Exchange History:")
                processedKeyExchanges.take(10).forEach { exchange ->
                    appendLine("  - $exchange")
                }
                if (processedKeyExchanges.size > 10) {
                    appendLine("  ... and ${processedKeyExchanges.size - 10} more")
                }
            }
        }
    }
    
    /**
     * Start periodic cleanup
     */
    private fun startPeriodicCleanup() {
        managerScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL.milliseconds)
                cleanupOldData()
            }
        }
    }
    
    /**
     * Clean up old processed messages and timestamps
     */
    private fun cleanupOldData() {
        val cutoffTime = Clock.System.now().toEpochMilliseconds() - MESSAGE_TIMEOUT
        var removedCount = 0
        
        // Clean up old message timestamps and corresponding processed messages
        val messagesToRemove = messageTimestamps.entries.filter { (_, timestamp) ->
            timestamp < cutoffTime
        }.map { it.key }

        processedLock.withLock {
            messagesToRemove.forEach { messageId ->
                messageTimestamps.remove(messageId)
                if (processedMessages.remove(messageId)) {
                    removedCount++
                }
            }
        }
        // The size cap is now enforced at insertion (see [recordProcessedMessage]); cleanup only
        // needs to expire entries older than the replay window.

        // Limit the size of processed key exchanges set
        if (processedKeyExchanges.size > MAX_PROCESSED_KEY_EXCHANGES) {
            val excess = processedKeyExchanges.size - MAX_PROCESSED_KEY_EXCHANGES
            val toRemove = processedKeyExchanges.take(excess)
            processedKeyExchanges.removeAll(toRemove.toSet())
        }
        
        if (removedCount > 0) {
            Log.d(TAG, "Cleaned up $removedCount old processed messages")
        }
    }
    
    /**
     * Clear all security data
     */
    fun clearAllData() {
        processedLock.withLock { processedMessages.clear() }
        processedKeyExchanges.clear()
        messageTimestamps.clear()
        noiseRateLimiter.resetAll() // panic parity with iOS resetAll
    }
    
    /**
     * Shutdown the manager
     */
    fun shutdown() {
        managerScope.cancel()
        clearAllData()
    }
}

/**
 * Delegate interface for security manager callbacks
 */
internal interface SecurityManagerDelegate {
    fun onKeyExchangeCompleted(peerID: String, peerPublicKeyData: ByteArray)
    fun sendHandshakeResponse(peerID: String, response: ByteArray)
    fun getPeerInfo(peerID: String): PeerInfo? // NEW: For signature verification
}
