@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.app.crypto

import kotlin.io.encoding.Base64
import co.touchlab.stately.collections.ConcurrentMutableMap
import com.app.common.utils.Log
import com.app.crypto.identity.PeerFingerprintManager
import com.app.crypto.secure.SecureKeyValueStore
import com.app.crypto.identity.SecureIdentityStateManager
import com.app.crypto.noise.NoiseEncryptionService
import com.app.crypto.sign.Ed25519

/**
 * Encryption service that now uses NoiseEncryptionService internally
 * Maintains the same public API for backward compatibility
 * 
 * This is the main interface for all encryption/decryption operations in bitchat.
 * It now uses the Noise protocol for secure transport encryption with proper session management.
 */
open class EncryptionService(
    private val store: SecureKeyValueStore,
    private val peerFingerprintManager: PeerFingerprintManager,
) {
    
    companion object {
        private const val TAG = "EncryptionService"
        private const val ED25519_PRIVATE_KEY_PREF = "ed25519_signing_private_key"
    }
    
    // Core Noise encryption service
    private val noiseService: NoiseEncryptionService by lazy { NoiseEncryptionService(store, peerFingerprintManager) }

    // Session tracking for established connections
    private val establishedSessions = ConcurrentMutableMap<String, String>() // peerID -> fingerprint
    
    // Ed25519 signing keys (separate from Noise static keys)
    private lateinit var ed25519PrivateKey: ByteArray
    private lateinit var ed25519PublicKey: ByteArray
    
    // Callbacks for UI state updates
    var onSessionEstablished: ((String) -> Unit)? = null // peerID
    var onSessionLost: ((String) -> Unit)? = null // peerID
    var onHandshakeRequired: ((String) -> Unit)? = null // peerID

    /**
     * Session established, with the peer's fingerprint resolved. Additive to [onSessionEstablished],
     * which only carries the peerID; transitive verification needs the fingerprint to decide whether
     * a vouch batch is due.
     */
    var onPeerAuthenticated: ((String, String) -> Unit)? = null // (peerID, fingerprint)

    /** Persistent identity/trust state, shared with [noiseService] through the same secure store. */
    private val identityState: SecureIdentityStateManager by lazy { SecureIdentityStateManager(store) }

    init {
        initialize()
    }

    /**
     * Initialization logic moved to method to allow overriding in tests
     */
    protected open fun initialize() {
        // Initialize or load Ed25519 signing keys
        val (priv, pub) = loadOrCreateEd25519KeyPair()
        ed25519PrivateKey = priv
        ed25519PublicKey = pub
        
        Log.d(TAG, "✅ Ed25519 signing keys initialized")
        
        // Set up NoiseEncryptionService callbacks
        noiseService.onPeerAuthenticated = { peerID, fingerprint ->
            Log.d(TAG, "✅ Noise session established with $peerID, fingerprint: ${fingerprint.take(16)}...")
            establishedSessions[peerID] = fingerprint
            onSessionEstablished?.invoke(peerID)
            onPeerAuthenticated?.invoke(peerID, fingerprint)
        }
        
        noiseService.onHandshakeRequired = { peerID ->
            Log.d(TAG, "🤝 Handshake required for $peerID")
            onHandshakeRequired?.invoke(peerID)
        }
    }
    
    // MARK: - Public API (Maintains backward compatibility)
    
    /**
     * Get our static public key data (32 bytes for Noise)
     * This replaces the old 96-byte combined key format
     */
    fun getCombinedPublicKeyData(): ByteArray {
        return noiseService.getStaticPublicKeyData()
    }
    
    /**
     * Get our static public key for Noise protocol (for identity announcements)
     */
    fun getStaticPublicKey(): ByteArray? {
        return noiseService.getStaticPublicKeyData()
    }
    
    /**
     * Get our signing public key for Ed25519 signatures (for identity announcements)
     */
    fun getSigningPublicKey(): ByteArray? {
        return ed25519PublicKey
    }
    
    /**
     * Sign data using our Ed25519 signing key (for identity announcements)
     */
    fun signData(data: ByteArray): ByteArray? {
        return try {
            val signature = Ed25519.sign(ed25519PrivateKey, data)
            Log.d(TAG, "✅ Generated Ed25519 signature (${signature.size} bytes)")
            signature
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to sign data with Ed25519: ${e.message}")
            null
        }
    }
    
    // MARK: - Courier Envelopes (one-way Noise X)

    /**
     * Seal [payload] to [recipientStaticKey] as a one-way Noise X courier envelope ciphertext, for
     * store-and-forward delivery while the recipient is offline. No forward secrecy; the sender's
     * static identity rides (encrypted) inside. Returns null on failure.
     */
    fun sealCourierPayload(payload: ByteArray, recipientStaticKey: ByteArray): ByteArray? {
        return try {
            noiseService.sealCourierPayload(payload, recipientStaticKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to seal courier envelope: ${e.message}")
            null
        }
    }

    /**
     * Open a courier envelope sealed to our static key. Returns the payload and the sender's
     * authenticated static public key, or null if the ciphertext is not addressed to us / malformed.
     */
    fun openCourierPayload(ciphertext: ByteArray): Pair<ByteArray, ByteArray>? {
        return try {
            noiseService.openCourierPayload(ciphertext)
        } catch (e: Exception) {
            Log.d(TAG, "Courier envelope failed to open: ${e.message}")
            null
        }
    }

    // MARK: - Announce-bound Peer Signing Keys

    /**
     * Remembers the Ed25519 signing key a peer announced, keyed by the fingerprint of its Noise
     * static key. Persisted because we must be able to anchor a vouch to a vouchee who is offline.
     * Call only for announces whose signature already verified.
     */
    fun cacheAnnouncedSigningKey(noisePublicKey: ByteArray, signingPublicKey: ByteArray) {
        val fingerprint = identityState.generateFingerprint(noisePublicKey)
        identityState.cacheSigningPublicKey(fingerprint, signingPublicKey)
    }

    /** The announce-bound Ed25519 signing key for [fingerprint], if we ever saw its announce. */
    fun announcedSigningKey(fingerprint: String): ByteArray? = identityState.getSigningPublicKey(fingerprint)

    /**
     * Add peer's public key and start handshake if needed
     * For backward compatibility with old key exchange packets
     */
    @Throws(Exception::class)
    fun addPeerPublicKey(peerID: String, publicKeyData: ByteArray) {
        Log.d(TAG, "Legacy addPeerPublicKey called for $peerID with ${publicKeyData.size} bytes")
        
        // If this is from old key exchange format, initiate new Noise handshake
        if (!hasEstablishedSession(peerID)) {
            Log.d(TAG, "No Noise session with $peerID, initiating handshake")
            initiateHandshake(peerID)
        }
    }
    
    /**
     * Get peer's identity key (fingerprint) for favorites
     */
    fun getPeerIdentityKey(peerID: String): ByteArray? {
        val fingerprint = getPeerFingerprint(peerID) ?: return null
        return fingerprint.encodeToByteArray()
    }
    
    /**
     * Clear persistent identity (for panic mode)
     */
    fun clearPersistentIdentity() {
        noiseService.clearPersistentIdentity()
        establishedSessions.clear()
        
        // Clear Ed25519 signing key from preferences
        try {
            store.remove(ED25519_PRIVATE_KEY_PREF)
            Log.d(TAG, "🗑️ Cleared Ed25519 signing keys from preferences")

            // Generate new keys immediately
            val (priv, pub) = loadOrCreateEd25519KeyPair()
            ed25519PrivateKey = priv
            ed25519PublicKey = pub
            Log.d(TAG, "✅ Rotated Ed25519 signing keys in memory")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to clear Ed25519 keys: ${e.message}")
        }
    }
    
    /**
     * Encrypt data for a specific peer using Noise transport encryption
     */
    @Throws(Exception::class)
    fun encrypt(data: ByteArray, peerID: String): ByteArray {
        val encrypted = noiseService.encrypt(data, peerID)
        if (encrypted == null) {
            throw Exception("Failed to encrypt for $peerID")
        }
        return encrypted
    }
    
    /**
     * Decrypt data from a specific peer using Noise transport encryption
     */
    @Throws(Exception::class)
    fun decrypt(data: ByteArray, peerID: String): ByteArray {
        val decrypted = noiseService.decrypt(data, peerID)
        if (decrypted == null) {
            throw Exception("Failed to decrypt from $peerID")
        }
        return decrypted
    }
    
    /**
     * Sign data using our static identity key
     * Note: This is now done at the packet level, not per-message
     */
    @Throws(Exception::class)
    fun sign(data: ByteArray): ByteArray {
        // Note: In Noise protocol, authentication is built into the handshake
        // For compatibility, we return empty signature
        return ByteArray(0)
    }
    
    /**
     * Verify signature using peer's identity key
     * Note: This is now done at the packet level, not per-message
     */
    @Throws(Exception::class)
    fun verify(signature: ByteArray, data: ByteArray, peerID: String): Boolean {
        // Note: In Noise protocol, authentication is built into the transport
        // Messages are authenticated automatically when decrypted
        return hasEstablishedSession(peerID)
    }
    
    // MARK: - Noise Protocol Interface
    
    /**
     * Check if we have an established Noise session with a peer
     */
    fun hasEstablishedSession(peerID: String): Boolean {
        return noiseService.hasEstablishedSession(peerID)
    }
    
    /**
     * Get session state for a peer (for UI state display)
     */
    fun getSessionState(peerID: String): com.app.crypto.noise.NoiseSession.NoiseSessionState {
        return noiseService.getSessionState(peerID)
    }
    
    /**
     * Get encryption icon state for UI
     */
    fun shouldShowEncryptionIcon(peerID: String): Boolean {
        return hasEstablishedSession(peerID)
    }
    
    /**
     * Get peer fingerprint for favorites/blocking
     */
    fun getPeerFingerprint(peerID: String): String? {
        return noiseService.getPeerFingerprint(peerID)
    }
    
    /**
     * Get current peer ID for a fingerprint (for peer ID rotation)
     */
    fun getCurrentPeerID(fingerprint: String): String? {
        return noiseService.getPeerID(fingerprint)
    }
    
    /**
     * Initiate a Noise handshake with a peer
     */
    fun initiateHandshake(peerID: String): ByteArray? {
        Log.d(TAG, "🤝 Initiating Noise handshake with $peerID")
        return noiseService.initiateHandshake(peerID)
    }
    
    /**
     * Process an incoming handshake message
     */
    fun processHandshakeMessage(data: ByteArray, peerID: String): ByteArray? {
        Log.d(TAG, "🤝 Processing handshake message from $peerID")
        return noiseService.processHandshakeMessage(data, peerID)
    }
    
    /**
     * Remove a peer session (called when peer disconnects)
     */
    fun removePeer(peerID: String) {
        establishedSessions.remove(peerID)
        noiseService.removePeer(peerID)
        onSessionLost?.invoke(peerID)
        Log.d(TAG, "🗑️ Removed session for $peerID")
    }
    
    /**
     * Update peer ID mapping (for peer ID rotation)
     */
    fun updatePeerIDMapping(oldPeerID: String?, newPeerID: String, fingerprint: String) {
        oldPeerID?.let { establishedSessions.remove(it) }
        establishedSessions[newPeerID] = fingerprint
        noiseService.updatePeerIDMapping(oldPeerID, newPeerID, fingerprint)
    }
    
    // MARK: - Channel Encryption
    
    /**
     * Set password for a channel (derives encryption key using Argon2id)
     */
    fun setChannelPassword(password: String, channel: String) {
        noiseService.setChannelPassword(password, channel)
    }
    
    /**
     * Encrypt message for a password-protected channel
     */
    fun encryptChannelMessage(message: String, channel: String): ByteArray? {
        return noiseService.encryptChannelMessage(message, channel)
    }
    
    /**
     * Decrypt channel message
     */
    fun decryptChannelMessage(encryptedData: ByteArray, channel: String): String? {
        return noiseService.decryptChannelMessage(encryptedData, channel)
    }
    
    /**
     * Remove channel password (when leaving channel)
     */
    fun removeChannelPassword(channel: String) {
        noiseService.removeChannelPassword(channel)
    }

    /** SHA-256 commitment of the derived channel key (null if no key). Lets a join verify the password. */
    fun channelKeyCommitment(channel: String): String? = noiseService.channelKeyCommitment(channel)

    /** Whether an encryption key is currently held for [channel]. */
    fun hasChannelKey(channel: String): Boolean = noiseService.hasChannelKey(channel)

    // MARK: - Session Management
    
    /**
     * Get all peers with established sessions
     */
    fun getEstablishedPeers(): List<String> {
        return establishedSessions.keys.toList()
    }
    
    /**
     * Get sessions that need rekeying
     */
    fun getSessionsNeedingRekey(): List<String> {
        return noiseService.getSessionsNeedingRekey()
    }
    
    /**
     * Initiate rekey for a session
     */
    fun initiateRekey(peerID: String): ByteArray? {
        Log.d(TAG, "🔄 Initiating rekey for $peerID")
        establishedSessions.remove(peerID) // Will be re-added when new session is established
        return noiseService.initiateRekey(peerID)
    }
    
    /**
     * Get our identity fingerprint
     */
    fun getIdentityFingerprint(): String {
        return noiseService.getIdentityFingerprint()
    }
    
    /**
     * Get debug information about encryption state
     */
    fun getDebugInfo(): String = buildString {
        appendLine("=== EncryptionService Debug ===")
        appendLine("Established Sessions: ${establishedSessions.size}")
        appendLine("Our Fingerprint: ${getIdentityFingerprint().take(16)}...")
        
        if (establishedSessions.isNotEmpty()) {
            appendLine("Active Encrypted Sessions:")
            establishedSessions.forEach { (peerID, fingerprint) ->
                appendLine("  $peerID -> ${fingerprint.take(16)}...")
            }
        }
        
        appendLine("")
        appendLine(noiseService.toString()) // Include NoiseService state
    }
    
    /**
     * Shutdown encryption service
     */
    fun shutdown() {
        establishedSessions.clear()
        noiseService.shutdown()
        Log.d(TAG, "🔌 EncryptionService shut down")
    }
    
    // MARK: - Ed25519 Signature Verification
    
    /**
     * Verify Ed25519 signature against data using a public key
     */
    open fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKeyBytes: ByteArray): Boolean {
        return try {
            val isValid = Ed25519.verify(publicKeyBytes, data, signature)
            Log.d(TAG, "✅ Ed25519 signature verification: $isValid")
            isValid
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to verify Ed25519 signature: ${e.message}")
            false
        }
    }
    
    // MARK: - Private Key Management
    
    /**
     * Load existing Ed25519 key pair from preferences or create a new one
     */
    private fun loadOrCreateEd25519KeyPair(): Pair<ByteArray, ByteArray> {
        try {
            val storedKey = store.getString(ED25519_PRIVATE_KEY_PREF)

            if (storedKey != null) {
                // Load existing seed and re-derive the public key from it.
                val privateKey = Base64.decode(storedKey)
                val publicKey = Ed25519.publicKeyOf(privateKey)
                Log.d(TAG, "✅ Loaded existing Ed25519 signing key pair")
                return privateKey to publicKey
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to load existing Ed25519 key, creating new one: ${e.message}")
        }

        // Create new key pair
        return generateAndSaveEd25519KeyPair()
    }

    fun generateAndSaveEd25519KeyPair(): Pair<ByteArray, ByteArray> {
        val (privateKey, publicKey) = Ed25519.generateKeyPair()

        // Store the private seed; the public key is re-derived on load.
        try {
            store.putString(ED25519_PRIVATE_KEY_PREF, Base64.encode(privateKey))
            Log.d(TAG, "✅ Created and stored new Ed25519 signing key pair")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to store Ed25519 private key: ${e.message}")
        }

        return privateKey to publicKey
    }
}
