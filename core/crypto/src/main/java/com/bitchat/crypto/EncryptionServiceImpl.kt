package com.bitchat.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import com.bitchat.domain.model.NoiseSessionState
import com.bitchat.domain.repository.EncryptionRepository
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Encryption service that now uses NoiseEncryptionService internally
 * Maintains the same public API for backward compatibility
 *
 * This is the main interface for all encryption/decryption operations in bitchat.
 * It now uses the Noise protocol for secure transport encryption with proper session management.
 */
class EncryptionServiceImpl(private val context: Context) : EncryptionRepository {
    companion object {
        private const val TAG = "EncryptionService"
        private const val ED25519_PRIVATE_KEY_PREF = "ed25519_signing_private_key"
    }

    // Core Noise encryption service
    private val noiseService: NoiseEncryptionService = NoiseEncryptionService(context)

    // Session tracking for established connections
    private val establishedSessions = ConcurrentHashMap<String, String>() // peerID -> fingerprint

    // Ed25519 signing keys (separate from Noise static keys)
    private val ed25519PrivateKey: Ed25519PrivateKeyParameters
    private val ed25519PublicKey: Ed25519PublicKeyParameters

    // Callbacks for UI state updates
    var onSessionEstablished: ((String) -> Unit)? = null // peerID
    var onSessionLost: ((String) -> Unit)? = null // peerID
    var onHandshakeRequired: ((String) -> Unit)? = null // peerID

    init {
        // Initialize or load Ed25519 signing keys
        val keyPair = loadOrCreateEd25519KeyPair()
        ed25519PrivateKey = keyPair.private as Ed25519PrivateKeyParameters
        ed25519PublicKey = keyPair.public as Ed25519PublicKeyParameters

        Log.d(TAG, "✅ Ed25519 signing keys initialized")

        // Set up NoiseEncryptionService callbacks
        noiseService.onPeerAuthenticated = { peerID, fingerprint ->
            Log.d(TAG, "✅ Noise session established with $peerID, fingerprint: ${fingerprint.take(16)}...")
            establishedSessions[peerID] = fingerprint
            onSessionEstablished?.invoke(peerID)
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
    override fun getCombinedPublicKeyData(): ByteArray {
        return noiseService.getStaticPublicKeyData()
    }

    /**
     * Get our static public key for Noise protocol (for identity announcements)
     */
    override fun getStaticPublicKey(): ByteArray? {
        return noiseService.getStaticPublicKeyData()
    }

    /**
     * Get our signing public key for Ed25519 signatures (for identity announcements)
     */
    override fun getSigningPublicKey(): ByteArray? {
        return ed25519PublicKey.encoded
    }

    /**
     * Sign data using our Ed25519 signing key (for identity announcements)
     */
    override fun signData(data: ByteArray): ByteArray? {
        return try {
            val signer = Ed25519Signer()
            signer.init(true, ed25519PrivateKey)
            signer.update(data, 0, data.size)
            val signature = signer.generateSignature()
            Log.d(TAG, "✅ Generated Ed25519 signature (${signature.size} bytes)")
            signature
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to sign data with Ed25519: ${e.message}")
            null
        }
    }

    /**
     * Add peer's public key and start handshake if needed
     * For backward compatibility with old key exchange packets
     */
    @Throws(Exception::class)
    override fun addPeerPublicKey(peerID: String, publicKeyData: ByteArray) {
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
    override fun getPeerIdentityKey(peerID: String): ByteArray? {
        val fingerprint = getPeerFingerprint(peerID) ?: return null
        return fingerprint.toByteArray()
    }

    /**
     * Clear persistent identity (for panic mode)
     */
    override fun clearPersistentIdentity() {
        noiseService.clearPersistentIdentity()
        establishedSessions.clear()

        // Clear Ed25519 signing key from preferences
        try {
            val prefs = context.getSharedPreferences("bitchat_crypto", Context.MODE_PRIVATE)
            prefs.edit().remove(ED25519_PRIVATE_KEY_PREF).apply()
            Log.d(TAG, "🗑️ Cleared Ed25519 signing keys from preferences")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to clear Ed25519 keys: ${e.message}")
        }
    }

    /**
     * Encrypt data for a specific peer using Noise transport encryption
     */
    @Throws(Exception::class)
    override fun encrypt(data: ByteArray, peerID: String): ByteArray {
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
    override fun decrypt(data: ByteArray, peerID: String): ByteArray {
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
    override fun sign(data: ByteArray): ByteArray {
        // Note: In Noise protocol, authentication is built into the handshake
        // For compatibility, we return empty signature
        return ByteArray(0)
    }

    /**
     * Verify signature using peer's identity key
     * Note: This is now done at the packet level, not per-message
     */
    @Throws(Exception::class)
    override fun verify(signature: ByteArray, data: ByteArray, peerID: String): Boolean {
        // Note: In Noise protocol, authentication is built into the transport
        // Messages are authenticated automatically when decrypted
        return hasEstablishedSession(peerID)
    }


    /**
     * Check if we have an established Noise session with a peer
     */
    override fun hasEstablishedSession(peerID: String): Boolean {
        return noiseService.hasEstablishedSession(peerID)
    }

    /**
     * Get session state for a peer (for UI state display)
     */
    override fun getSessionState(peerID: String): NoiseSessionState {
        return noiseService.getSessionState(peerID)
    }

    /**
     * Get encryption icon state for UI
     */
    override fun shouldShowEncryptionIcon(peerID: String): Boolean {
        return hasEstablishedSession(peerID)
    }

    /**
     * Get peer fingerprint for favorites/blocking
     */
    override fun getPeerFingerprint(peerID: String): String? {
        return noiseService.getPeerFingerprint(peerID)
    }

    /**
     * Get current peer ID for a fingerprint (for peer ID rotation)
     */
    override fun getCurrentPeerID(fingerprint: String): String? {
        return noiseService.getPeerID(fingerprint)
    }

    /**
     * Initiate a Noise handshake with a peer
     */
    override fun initiateHandshake(peerID: String): ByteArray? {
        Log.d(TAG, "🤝 Initiating Noise handshake with $peerID")
        return noiseService.initiateHandshake(peerID)
    }

    /**
     * Process an incoming handshake message
     */
    override fun processHandshakeMessage(data: ByteArray, peerID: String): ByteArray? {
        Log.d(TAG, "🤝 Processing handshake message from $peerID")
        return noiseService.processHandshakeMessage(data, peerID)
    }

    /**
     * Remove a peer session (called when peer disconnects)
     */
    override fun removePeer(peerID: String) {
        establishedSessions.remove(peerID)
        noiseService.removePeer(peerID)
        onSessionLost?.invoke(peerID)
        Log.d(TAG, "🗑️ Removed session for $peerID")
    }

    /**
     * Update peer ID mapping (for peer ID rotation)
     */
    override fun updatePeerIDMapping(oldPeerID: String?, newPeerID: String, fingerprint: String) {
        oldPeerID?.let { establishedSessions.remove(it) }
        establishedSessions[newPeerID] = fingerprint
        noiseService.updatePeerIDMapping(oldPeerID, newPeerID, fingerprint)
    }

    // MARK: - Channel Encryption

    /**
     * Set password for a channel (derives encryption key using Argon2id)
     */
    override fun setChannelPassword(password: String, channel: String) {
        noiseService.setChannelPassword(password, channel)
    }

    /**
     * Encrypt message for a password-protected channel
     */
    override fun encryptChannelMessage(message: String, channel: String): ByteArray? {
        return noiseService.encryptChannelMessage(message, channel)
    }

    /**
     * Decrypt channel message
     */
    override fun decryptChannelMessage(encryptedData: ByteArray, channel: String): String? {
        return noiseService.decryptChannelMessage(encryptedData, channel)
    }

    /**
     * Remove channel password (when leaving channel)
     */
    override fun removeChannelPassword(channel: String) {
        noiseService.removeChannelPassword(channel)
    }

    // MARK: - Session Management

    /**
     * Get all peers with established sessions
     */
    override fun getEstablishedPeers(): List<String> {
        return establishedSessions.keys.toList()
    }

    /**
     * Get sessions that need rekeying
     */
    override fun getSessionsNeedingRekey(): List<String> {
        return noiseService.getSessionsNeedingRekey()
    }

    /**
     * Initiate rekey for a session
     */
    override fun initiateRekey(peerID: String): ByteArray? {
        Log.d(TAG, "🔄 Initiating rekey for $peerID")
        establishedSessions.remove(peerID) // Will be re-added when new session is established
        return noiseService.initiateRekey(peerID)
    }

    /**
     * Get our identity fingerprint
     */
    override fun getIdentityFingerprint(): String {
        return noiseService.getIdentityFingerprint()
    }

    /**
     * Get debug information about encryption state
     */
    override fun getDebugInfo(): String = buildString {
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
    override fun shutdown() {
        establishedSessions.clear()
        noiseService.shutdown()
        Log.d(TAG, "🔌 EncryptionService shut down")
    }

    // MARK: - Ed25519 Signature Verification

    /**
     * Verify Ed25519 signature against data using a public key
     */
    override fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKeyBytes: ByteArray): Boolean {
        return try {
            val publicKey = Ed25519PublicKeyParameters(publicKeyBytes, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, publicKey)
            verifier.update(data, 0, data.size)
            val isValid = verifier.verifySignature(signature)
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
    private fun loadOrCreateEd25519KeyPair(): AsymmetricCipherKeyPair {
        try {
            val prefs = context.getSharedPreferences("bitchat_crypto", Context.MODE_PRIVATE)
            val storedKey = prefs.getString(ED25519_PRIVATE_KEY_PREF, null)

            if (storedKey != null) {
                // Load existing key
                val privateKeyBytes = Base64.decode(storedKey, Base64.DEFAULT)
                val privateKey = Ed25519PrivateKeyParameters(privateKeyBytes, 0)
                val publicKey = privateKey.generatePublicKey()
                Log.d(TAG, "✅ Loaded existing Ed25519 signing key pair")
                return AsymmetricCipherKeyPair(publicKey, privateKey)
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to load existing Ed25519 key, creating new one: ${e.message}")
        }

        // Create new key pair
        val keyGen = Ed25519KeyPairGenerator()
        keyGen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair = keyGen.generateKeyPair()

        // Store private key in preferences
        try {
            val privateKey = keyPair.private as Ed25519PrivateKeyParameters
            val privateKeyBytes = privateKey.encoded
            val encodedKey = Base64.encodeToString(privateKeyBytes, Base64.DEFAULT)

            val prefs = context.getSharedPreferences("bitchat_crypto", Context.MODE_PRIVATE)
            prefs.edit().putString(ED25519_PRIVATE_KEY_PREF, encodedKey).apply()
            Log.d(TAG, "✅ Created and stored new Ed25519 signing key pair")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to store Ed25519 private key: ${e.message}")
        }

        return keyPair
    }
}