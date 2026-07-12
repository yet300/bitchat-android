package com.app.crypto.noise

import com.app.common.utils.Log
import com.app.common.encoding.hexEncodedString
import com.app.crypto.identity.SecureIdentityStateManager
import com.app.crypto.identity.PeerFingerprintManager
import com.app.crypto.secure.SecureKeyValueStore
import com.app.crypto.hash.Sha256
import com.app.crypto.prekey.LocalPrekeyStore
import com.app.crypto.prekey.PublicPrekey
import com.app.crypto.sign.Ed25519
import com.app.crypto.noise.southernstorm.protocol.HandshakeState

/**
 * Main Noise encryption service - 100% compatible with iOS implementation
 * 
 * This service manages:
 * - Static identity keys (persistent across sessions)
 * - Noise session management for each peer
 * - Channel encryption using password-derived keys
 * - Peer fingerprint mapping and identity persistence
 */
internal class NoiseEncryptionService(
    store: SecureKeyValueStore,
    private val fingerprintManager: PeerFingerprintManager,
) {
    
    companion object {
        private const val TAG = "NoiseEncryptionService"
        
        // Session limits for performance and security
        private const val REKEY_TIME_LIMIT = NoiseConstants.REKEY_TIME_LIMIT_MS // 1 hour (same as iOS)
        private const val REKEY_MESSAGE_LIMIT = NoiseConstants.REKEY_MESSAGE_LIMIT_ENCRYPTION // 1k messages (matches iOS) (same as iOS)

        // Courier envelopes: one-way Noise X, domain-separated from interactive XX handshakes.
        private const val COURIER_PROTOCOL_NAME = "Noise_X_25519_ChaChaPoly_SHA256"
        private val COURIER_PROLOGUE = "bitchat-courier-v1".encodeToByteArray()
        // X message = e(32) + encrypted static s(32+16) + AEAD tag over payload(16); pad generously.
        private const val COURIER_HANDSHAKE_OVERHEAD = 128

        // Prekey-sealed envelopes (v2): same one-way Noise X, but the responder static is a one-time
        // prekey (not the identity), and the prologue is bound to the specific prekey ID so a
        // ciphertext cannot be replayed against a different prekey.
        private val PREKEY_PROLOGUE_PREFIX = "bitchat-prekey-v1".encodeToByteArray()

        private fun prekeyPrologue(prekeyID: UInt): ByteArray {
            val prologue = ByteArray(PREKEY_PROLOGUE_PREFIX.size + 4)
            PREKEY_PROLOGUE_PREFIX.copyInto(prologue)
            for (i in 0 until 4) {
                prologue[PREKEY_PROLOGUE_PREFIX.size + i] = (prekeyID shr (8 * (3 - i))).toByte()
            }
            return prologue
        }
    }
    
    // Static identity key (persistent across app restarts) - loaded from secure storage
    private var staticIdentityPrivateKey: ByteArray
    private var staticIdentityPublicKey: ByteArray
    
    // Ed25519 signing key (persistent across app restarts) - loaded from secure storage
    private var signingPrivateKey: ByteArray
    private var signingPublicKey: ByteArray
    
    // Session management
    private lateinit var sessionManager: NoiseSessionManager

    // Identity management for peer ID rotation support
    private val identityStateManager: SecureIdentityStateManager

    // One-time prekey privates for forward-secret courier sealing (lazy generation on first bundle
    // request). Persisted behind the same secure store as the identity keys; wiped on panic.
    private val localPrekeys: LocalPrekeyStore

    // Callbacks
    var onPeerAuthenticated: ((String, String) -> Unit)? = null // (peerID, fingerprint)
    var onHandshakeRequired: ((String) -> Unit)? = null // peerID needs handshake
    
    init {
        // Initialize identity state manager for persistent storage
        identityStateManager = SecureIdentityStateManager(store)
        localPrekeys = LocalPrekeyStore(store)

        // Load or create keys - temporary placeholders
        staticIdentityPrivateKey = ByteArray(32)
        staticIdentityPublicKey = ByteArray(32)
        signingPrivateKey = ByteArray(32)
        signingPublicKey = ByteArray(32)
        
        loadOrGenerateKeys()
        
        // Initialize session manager
        initializeSessionManager()
    }
    
    private fun initializeSessionManager() {
        // Create new session manager with current keys
        val localPeerID = calculateFingerprint(staticIdentityPublicKey).take(16)
        sessionManager = NoiseSessionManager(staticIdentityPrivateKey, staticIdentityPublicKey, localPeerID)
        
        // Set up session callbacks
        sessionManager.onSessionEstablished = { peerID, remoteStaticKey ->
            handleSessionEstablished(peerID, remoteStaticKey)
        }
        
        // Ensure any other callbacks are wired if needed
        // sessionManager.onSessionFailed could be wired if we exposed it
    }
    
    private fun loadOrGenerateKeys() {
        // Load or create static identity key (persistent across sessions)
        val loadedKeyPair = identityStateManager.loadStaticKey()
        if (loadedKeyPair != null) {
            staticIdentityPrivateKey = loadedKeyPair.first
            staticIdentityPublicKey = loadedKeyPair.second
            Log.d(TAG, "Loaded existing static identity key: ${calculateFingerprint(staticIdentityPublicKey)}")
        } else {
            // Generate new identity key pair
            val keyPair = generateKeyPair()
            staticIdentityPrivateKey = keyPair.first
            staticIdentityPublicKey = keyPair.second
            
            // Save to secure storage
            identityStateManager.saveStaticKey(staticIdentityPrivateKey, staticIdentityPublicKey)
            Log.d(TAG, "Generated and saved new static identity key")
        }
        
        // Load or create Ed25519 signing key (persistent across sessions)
        val loadedSigningKeyPair = identityStateManager.loadSigningKey()
        if (loadedSigningKeyPair != null) {
            signingPrivateKey = loadedSigningKeyPair.first
            signingPublicKey = loadedSigningKeyPair.second
            Log.d(TAG, "Loaded existing Ed25519 signing key")
        } else {
            // Generate new Ed25519 signing key pair
            val signingKeyPair = generateEd25519KeyPair()
            signingPrivateKey = signingKeyPair.first
            signingPublicKey = signingKeyPair.second
            
            // Save to secure storage
            identityStateManager.saveSigningKey(signingPrivateKey, signingPublicKey)
            Log.d(TAG, "Generated and saved new Ed25519 signing key")
        }
    }

    // MARK: - Public Interface
    
    /**
     * Get our static public key data for sharing (32 bytes)
     */
    fun getStaticPublicKeyData(): ByteArray {
        return staticIdentityPublicKey.copyOf()
    }

    /**
     * Get our signing public key data for sharing (32 bytes)
     */
    fun getSigningPublicKeyData(): ByteArray {
        return signingPublicKey.copyOf()
    }
    
    /**
     * Get our identity fingerprint (SHA-256 hash of static public key)
     */
    fun getIdentityFingerprint(): String {
        val hash = Sha256.digest(staticIdentityPublicKey)
        return hash.hexEncodedString()
    }
    
    /**
     * Get peer's public key data (if we have a session)
     */
    fun getPeerPublicKeyData(peerID: String): ByteArray? {
        return sessionManager.getRemoteStaticKey(peerID)
    }
    
    /**
     * Clear persistent identity (for panic mode)
     */
    fun clearPersistentIdentity() {
        Log.w(TAG, "🚨 Panic Mode: Clearing persistent identity and rotating in-memory keys")
        
        // 1. Clear storage
        identityStateManager.clearIdentityKeysImmediate()
        // One-time prekey privates go with the identity they were bound to.
        localPrekeys.wipe()

        // 2. Clear all sessions immediately
        if (::sessionManager.isInitialized) {
            sessionManager.shutdown()
        }
        
        // 3. Regenerate keys immediately (in-memory rotation)
        loadOrGenerateKeys()
        
        // 4. Re-initialize SessionManager with new keys
        initializeSessionManager()
        
        Log.d(TAG, "✅ Identity cleared and keys rotated")
    }
    
    // MARK: - Handshake Management
    
    /**
     * Initiate a Noise handshake with a peer
     * Returns the first handshake message to send
     */
    fun initiateHandshake(peerID: String): ByteArray? {
        return try {
            sessionManager.initiateHandshake(peerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate handshake with $peerID: ${e.message}")
            null
        }
    }
    
    /**
     * Process an incoming handshake message
     * Returns response message if needed, null if handshake complete or failed
     */
    fun processHandshakeMessage(data: ByteArray, peerID: String): ByteArray? {
        return try {
            sessionManager.processHandshakeMessage(peerID, data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process handshake from $peerID: ${e.message}")
            null
        }
    }
    
    /**
     * Check if we have an established session with a peer
     */
    fun hasEstablishedSession(peerID: String): Boolean {
        return sessionManager.hasEstablishedSession(peerID)
    }
    
    /**
     * Get session state for a peer (for UI state display)
     */
    fun getSessionState(peerID: String): NoiseSession.NoiseSessionState {
        return sessionManager.getSessionState(peerID)
    }
    
    // MARK: - Encryption/Decryption
    
    /**
     * Encrypt data for a specific peer using established Noise session
     */
    fun encrypt(data: ByteArray, peerID: String): ByteArray? {
        if (!hasEstablishedSession(peerID)) {
            Log.w(TAG, "No established session with $peerID, handshake required. TODO: IMPLEMENT HANDSHAKE INIT")
            onHandshakeRequired?.invoke(peerID)
            return null
        }
        
        return try {
            sessionManager.encrypt(data, peerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt for $peerID: ${e.message}")
            null
        }
    }
    
    /**
     * Decrypt data from a specific peer using established Noise session
     */
    fun decrypt(encryptedData: ByteArray, peerID: String): ByteArray? {
        if (!hasEstablishedSession(peerID)) {
            Log.w(TAG, "No established session with $peerID")
            return null
        }
        
        return try {
            sessionManager.decrypt(encryptedData, peerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt from $peerID: ${e.message}")
            null
        }
    }
    
    // MARK: - Courier Envelopes (one-way Noise X)

    /**
     * Encrypt a payload to a peer's known static key without an interactive handshake
     * (Noise X pattern), for store-and-forward courier envelopes carried while the recipient is
     * offline. Byte-compatible with the reference iOS `sealCourierPayload`.
     *
     * One-way messages have NO forward secrecy: a later compromise of the recipient's static key
     * exposes envelopes captured in transit. Use an established session whenever the peer is
     * reachable. The initiator's static rides (encrypted) inside via the X `s` token, so the
     * recipient authenticates the sender from the ciphertext alone.
     */
    fun sealCourierPayload(payload: ByteArray, recipientStaticKey: ByteArray): ByteArray {
        val handshake = HandshakeState(COURIER_PROTOCOL_NAME, HandshakeState.INITIATOR)
        try {
            handshake.setPrologue(COURIER_PROLOGUE, 0, COURIER_PROLOGUE.size)
            handshake.localKeyPair?.setPrivateKey(staticIdentityPrivateKey, 0)
                ?: throw NoiseEncryptionError.InvalidMessage
            handshake.remotePublicKey?.setPublicKey(recipientStaticKey, 0)
                ?: throw NoiseEncryptionError.InvalidMessage
            handshake.start()
            val buffer = ByteArray(payload.size + COURIER_HANDSHAKE_OVERHEAD)
            val length = handshake.writeMessage(buffer, 0, payload, 0, payload.size)
            return buffer.copyOf(length)
        } finally {
            handshake.destroy()
        }
    }

    /**
     * Decrypt a courier envelope sealed to our static key. Returns the payload and the sender's
     * authenticated static public key (the X `ss` DH binds the sender's identity to the ciphertext).
     * Byte-compatible with the reference iOS `openCourierPayload`.
     */
    fun openCourierPayload(envelopeCiphertext: ByteArray): Pair<ByteArray, ByteArray> {
        val handshake = HandshakeState(COURIER_PROTOCOL_NAME, HandshakeState.RESPONDER)
        try {
            handshake.setPrologue(COURIER_PROLOGUE, 0, COURIER_PROLOGUE.size)
            handshake.localKeyPair?.setPrivateKey(staticIdentityPrivateKey, 0)
                ?: throw NoiseEncryptionError.InvalidMessage
            handshake.start()
            val payloadBuffer = ByteArray(envelopeCiphertext.size)
            val length = handshake.readMessage(envelopeCiphertext, 0, envelopeCiphertext.size, payloadBuffer, 0)
            if (handshake.hasRemotePublicKey() != true) throw NoiseEncryptionError.InvalidMessage
            val senderStaticKey = ByteArray(32)
            handshake.remotePublicKey?.getPublicKey(senderStaticKey, 0)
                ?: throw NoiseEncryptionError.InvalidMessage
            return payloadBuffer.copyOf(length) to senderStaticKey
        } finally {
            handshake.destroy()
        }
    }

    // MARK: - One-Time Prekey Envelopes (forward-secret Noise X, envelope v2)

    /**
     * Encrypt [payload] to one of the recipient's gossiped one-time prekeys (Noise X where the
     * responder static is the prekey, not the identity key). Unlike [sealCourierPayload], this is
     * forward secret: once the recipient consumes the prekey and its grace window lapses, the
     * private key is deleted and captured ciphertext becomes undecryptable even if the recipient's
     * identity key is later compromised. The initiator's static still rides (encrypted) inside, so
     * the recipient authenticates the sender exactly as with static-sealed envelopes.
     * Byte-compatible with the reference iOS `sealPrekeyPayload`.
     */
    fun sealPrekeyPayload(payload: ByteArray, recipientPrekeyID: UInt, recipientPrekey: ByteArray): ByteArray {
        val prologue = prekeyPrologue(recipientPrekeyID)
        val handshake = HandshakeState(COURIER_PROTOCOL_NAME, HandshakeState.INITIATOR)
        try {
            handshake.setPrologue(prologue, 0, prologue.size)
            handshake.localKeyPair?.setPrivateKey(staticIdentityPrivateKey, 0)
                ?: throw NoiseEncryptionError.InvalidMessage
            handshake.remotePublicKey?.setPublicKey(recipientPrekey, 0)
                ?: throw NoiseEncryptionError.InvalidMessage
            handshake.start()
            val buffer = ByteArray(payload.size + COURIER_HANDSHAKE_OVERHEAD)
            val length = handshake.writeMessage(buffer, 0, payload, 0, payload.size)
            return buffer.copyOf(length)
        } finally {
            handshake.destroy()
        }
    }

    /**
     * Decrypt an envelope sealed to one of our one-time prekeys. On success the prekey is marked
     * consumed (its private survives a 48h grace window for spray-and-wait redeliveries, then is
     * deleted for good). Returns the payload, the sender's authenticated static key, and whether
     * this open actually retired the prekey — false for a redelivery of already-consumed mail — so
     * the caller re-gossips the shrunken bundle only when it changed.
     *
     * Throws [NoiseEncryptionError.UnknownPrekey] when the prekey ID is unknown or grace-expired.
     * Byte-compatible with the reference iOS `openPrekeyPayload`.
     */
    fun openPrekeyPayload(envelopeCiphertext: ByteArray, prekeyID: UInt): PrekeyOpenResult {
        val prekeyPrivate = localPrekeys.privateKey(prekeyID) ?: throw NoiseEncryptionError.UnknownPrekey
        val prologue = prekeyPrologue(prekeyID)
        val handshake = HandshakeState(COURIER_PROTOCOL_NAME, HandshakeState.RESPONDER)
        try {
            handshake.setPrologue(prologue, 0, prologue.size)
            handshake.localKeyPair?.setPrivateKey(prekeyPrivate, 0)
                ?: throw NoiseEncryptionError.InvalidMessage
            handshake.start()
            val payloadBuffer = ByteArray(envelopeCiphertext.size)
            val length = handshake.readMessage(envelopeCiphertext, 0, envelopeCiphertext.size, payloadBuffer, 0)
            if (handshake.hasRemotePublicKey() != true) throw NoiseEncryptionError.InvalidMessage
            val senderStaticKey = ByteArray(32)
            handshake.remotePublicKey?.getPublicKey(senderStaticKey, 0)
                ?: throw NoiseEncryptionError.InvalidMessage
            val consumed = localPrekeys.markConsumed(prekeyID)
            return PrekeyOpenResult(payloadBuffer.copyOf(length), senderStaticKey, consumed)
        } finally {
            prekeyPrivate.fill(0)
            handshake.destroy()
        }
    }

    /** Current unconsumed public prekeys for the gossiped bundle, minting the initial batch lazily. */
    fun currentBundlePrekeys(): Pair<List<PublicPrekey>, ULong> = localPrekeys.currentBundlePrekeys()

    /** Prune dead prekeys and top the batch back up when consumption runs it low. */
    fun replenishPrekeysIfNeeded(): Boolean = localPrekeys.replenishIfNeeded()

    // MARK: - Peer Management

    /**
     * Get fingerprint for a peer (returns null if peer unknown)
     */
    fun getPeerFingerprint(peerID: String): String? {
        return fingerprintManager.getFingerprintForPeer(peerID)
    }
    
    /**
     * Get current peer ID for a fingerprint (returns null if not currently online)
     */
    fun getPeerID(fingerprint: String): String? {
        return fingerprintManager.getPeerIDForFingerprint(fingerprint)
    }
    
    /**
     * Remove a peer session (called when peer disconnects)
     */
    fun removePeer(peerID: String) {
        sessionManager.removeSession(peerID)
        
        // Clean up fingerprint mappings via centralized manager
        fingerprintManager.removePeer(peerID)
    }
    
    /**
     * Update peer ID mapping (for peer ID rotation)
     * This allows favorites/blocking to persist across peer ID changes
     */
    fun updatePeerIDMapping(oldPeerID: String?, newPeerID: String, fingerprint: String) {
        // Use centralized fingerprint manager for peer ID rotation
        fingerprintManager.updatePeerIDMapping(oldPeerID, newPeerID, fingerprint)
    }
    
    // MARK: - Session Maintenance
    
    /**
     * Get sessions that need rekey based on time or message count
     */
    fun getSessionsNeedingRekey(): List<String> {
        return sessionManager.getSessionsNeedingRekey()
    }
    
    /**
     * Initiate rekey for a session (replaces old session with new handshake)
     */
    fun initiateRekey(peerID: String): ByteArray? {
        Log.d(TAG, "Initiating rekey for session with $peerID")
        
        // Remove old session
        sessionManager.removeSession(peerID)
        
        // Start new handshake
        return initiateHandshake(peerID)
    }
    
    // MARK: - Private Helpers
    
    /**
     * Generate a new Curve25519 key pair using the real Noise library
     * Returns (privateKey, publicKey) as 32-byte arrays
     */
    private fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        try {
            val dhState = com.app.crypto.noise.southernstorm.protocol.Noise.createDH("25519")
            dhState.generateKeyPair()
            
            val privateKey = ByteArray(32)
            val publicKey = ByteArray(32)
            
            dhState.getPrivateKey(privateKey, 0)
            dhState.getPublicKey(publicKey, 0)
            
            dhState.destroy()
            
            return Pair(privateKey, publicKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate key pair: ${e.message}")
            throw e
        }
    }
    
    /**
     * Handle session establishment (called when Noise handshake completes)
     */
    private fun handleSessionEstablished(peerID: String, remoteStaticKey: ByteArray) {
        // Store fingerprint mapping via centralized manager
        // This is the ONLY place where fingerprints are stored - after successful Noise handshake
        fingerprintManager.storeFingerprintForPeer(peerID, remoteStaticKey)
        
        // Calculate fingerprint for logging and callback
        val fingerprint = calculateFingerprint(remoteStaticKey)
        
        Log.d(TAG, "Session established with $peerID, fingerprint: ${fingerprint.take(16)}...")
        
        // Notify about authentication
        onPeerAuthenticated?.invoke(peerID, fingerprint)
    }
    
    /**
     * Calculate fingerprint from public key (SHA-256 hash)
     */
    private fun calculateFingerprint(publicKey: ByteArray): String {
        val hash = Sha256.digest(publicKey)
        return hash.hexEncodedString()
    }
    
    /**
     * Sign data with our Ed25519 signing key
     */
    fun signData(data: ByteArray): ByteArray? {
        return try {
            signWithEd25519(data, signingPrivateKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign data: ${e.message}")
            null
        }
    }

    /**
     * Verify signature with a public key
     */
    fun verifySignature(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean {
        return try {
            verifyWithEd25519(signature, data, publicKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify signature: ${e.message}")
            false
        }
    }

    /**
     * Generate a new Ed25519 key pair for signing using BouncyCastle
     * Returns (privateKey, publicKey) as 32-byte arrays
     */
    private fun generateEd25519KeyPair(): Pair<ByteArray, ByteArray> = Ed25519.generateKeyPair()

    /**
     * Sign data with Ed25519 private key using BouncyCastle
     */
    private fun signWithEd25519(data: ByteArray, privateKey: ByteArray): ByteArray =
        Ed25519.sign(privateKey, data)

    /**
     * Verify Ed25519 signature using BouncyCastle
     */
    private fun verifyWithEd25519(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean =
        Ed25519.verify(publicKey, data, signature)

    /**
     * Clean shutdown
     */
    fun shutdown() {
        if (::sessionManager.isInitialized) {
            sessionManager.shutdown()
        }
        // No need to clear fingerprints here - they are managed centrally
    }
}

/**
 * Result of opening a prekey-sealed (v2) courier envelope: the recovered payload, the sender's
 * authenticated static key, and whether this open actually retired the prekey (false for a
 * redelivery of already-consumed mail).
 */
internal class PrekeyOpenResult(
    val payload: ByteArray,
    val senderStaticKey: ByteArray,
    val consumedPrekey: Boolean,
)

/**
 * Noise-specific errors
 */
internal sealed class NoiseEncryptionError(message: String) : Exception(message) {
    object HandshakeRequired : NoiseEncryptionError("Handshake required before encryption")
    object SessionNotEstablished : NoiseEncryptionError("No established Noise session")
    object InvalidMessage : NoiseEncryptionError("Invalid message format")
    object UnknownPrekey : NoiseEncryptionError("Unknown or grace-expired one-time prekey")
    class HandshakeFailed(cause: Throwable) : NoiseEncryptionError("Handshake failed: ${cause.message}")
}
