@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.app.crypto.identity

import android.content.Context
import com.app.crypto.secure.SecureKeyValueStore
import com.app.crypto.secure.TinkSecureKeyValueStore
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import com.app.common.utils.Log
import com.app.common.encoding.hexEncodedString

/**
 * Manages persistent identity storage and peer ID rotation - 100% compatible with iOS implementation
 *
 * Handles:
 * - Static identity key persistence across app sessions
 * - Secure storage backed by Google Tink ([SecureKeyValueStore])
 * - Fingerprint calculation and identity validation
 */
class SecureIdentityStateManager(private val context: Context) {
    
    companion object {
        private const val TAG = "SecureIdentityStateManager"
        private const val PREFS_NAME = "bitchat_identity"
        private const val KEY_STATIC_PRIVATE_KEY = "static_private_key"
        private const val KEY_STATIC_PUBLIC_KEY = "static_public_key"
        private const val KEY_SIGNING_PRIVATE_KEY = "signing_private_key"
        private const val KEY_SIGNING_PUBLIC_KEY = "signing_public_key"
        private const val KEY_VERIFIED_FINGERPRINTS = "verified_fingerprints"
        private const val KEY_CACHED_PEER_FINGERPRINTS = "cached_peer_fingerprints"
        private const val KEY_CACHED_PEER_NOISE_KEYS = "cached_peer_noise_keys"
        private const val KEY_CACHED_NOISE_FINGERPRINTS = "cached_noise_fingerprints"
        private const val KEY_CACHED_FINGERPRINT_NICKNAMES = "cached_fingerprint_nicknames"
    }
    
    private val store: SecureKeyValueStore = TinkSecureKeyValueStore(context, PREFS_NAME)
    private val lock = Any()
    
    // MARK: - Static Key Management
    
    /**
     * Load saved static key pair
     * Returns (privateKey, publicKey) or null if none exists
     */
    fun loadStaticKey(): Pair<ByteArray, ByteArray>? {
        return try {
            val privateKeyString = store.getString(KEY_STATIC_PRIVATE_KEY)
            val publicKeyString = store.getString(KEY_STATIC_PUBLIC_KEY)
            
            if (privateKeyString != null && publicKeyString != null) {
                val privateKey = Base64.Default.decode(privateKeyString)
                val publicKey = Base64.Default.decode(publicKeyString)
                
                // Validate key sizes
                if (privateKey.size == 32 && publicKey.size == 32) {
                    Log.d(TAG, "Loaded static identity key from secure storage")
                    Pair(privateKey, publicKey)
                } else {
                    Log.w(TAG, "Invalid key sizes in storage, returning null")
                    null
                }
            } else {
                Log.d(TAG, "No static identity key found in storage")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load static key: ${e.message}")
            null
        }
    }
    
    /**
     * Save static key pair to secure storage
     */
    fun saveStaticKey(privateKey: ByteArray, publicKey: ByteArray) {
        try {
            // Validate key sizes
            if (privateKey.size != 32 || publicKey.size != 32) {
                throw IllegalArgumentException("Invalid key sizes: private=${privateKey.size}, public=${publicKey.size}")
            }
            
            val privateKeyString = Base64.Default.encode(privateKey)
            val publicKeyString = Base64.Default.encode(publicKey)
            
            store.putString(KEY_STATIC_PRIVATE_KEY, privateKeyString)
            store.putString(KEY_STATIC_PUBLIC_KEY, publicKeyString)
            
            Log.d(TAG, "Saved static identity key to secure storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save static key: ${e.message}")
            throw e
        }
    }

    // MARK: - Signing Key Management

    /**
     * Load saved signing key pair
     * Returns (privateKey, publicKey) or null if none exists
     */
    fun loadSigningKey(): Pair<ByteArray, ByteArray>? {
        return try {
            val privateKeyString = store.getString(KEY_SIGNING_PRIVATE_KEY)
            val publicKeyString = store.getString(KEY_SIGNING_PUBLIC_KEY)
            
            if (privateKeyString != null && publicKeyString != null) {
                val privateKey = Base64.Default.decode(privateKeyString)
                val publicKey = Base64.Default.decode(publicKeyString)
                
                // Validate key sizes
                if (privateKey.size == 32 && publicKey.size == 32) {
                    Log.d(TAG, "Loaded Ed25519 signing key from secure storage")
                    Pair(privateKey, publicKey)
                } else {
                    Log.w(TAG, "Invalid signing key sizes in storage, returning null")
                    null
                }
            } else {
                Log.d(TAG, "No Ed25519 signing key found in storage")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load signing key: ${e.message}")
            null
        }
    }

    /**
     * Save signing key pair to secure storage
     */
    fun saveSigningKey(privateKey: ByteArray, publicKey: ByteArray) {
        try {
            // Validate key sizes
            if (privateKey.size != 32 || publicKey.size != 32) {
                throw IllegalArgumentException("Invalid signing key sizes: private=${privateKey.size}, public=${publicKey.size}")
            }
            
            val privateKeyString = Base64.Default.encode(privateKey)
            val publicKeyString = Base64.Default.encode(publicKey)
            
            store.putString(KEY_SIGNING_PRIVATE_KEY, privateKeyString)
            store.putString(KEY_SIGNING_PUBLIC_KEY, publicKeyString)
            
            Log.d(TAG, "Saved Ed25519 signing key to secure storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save signing key: ${e.message}")
            throw e
        }
    }
    
    // MARK: - Fingerprint Generation
    
    /**
     * Generate fingerprint from public key (SHA-256 hash)
     */
    fun generateFingerprint(publicKeyData: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKeyData)
        return hash.hexEncodedString()
    }
    
    /**
     * Validate fingerprint format
     */
    fun isValidFingerprint(fingerprint: String): Boolean {
        // SHA-256 fingerprint should be 64 hex characters
        return fingerprint.matches(Regex("^[a-fA-F0-9]{64}$"))
    }

    // MARK: - Verified Fingerprints

    fun getVerifiedFingerprints(): Set<String> {
        return store.getStringSet(KEY_VERIFIED_FINGERPRINTS) ?: emptySet()
    }

    fun isVerifiedFingerprint(fingerprint: String): Boolean {
        return getVerifiedFingerprints().contains(fingerprint)
    }

    fun setVerifiedFingerprint(fingerprint: String, verified: Boolean) {
        if (!isValidFingerprint(fingerprint)) return
        synchronized(lock) {
            val current = store.getStringSet(KEY_VERIFIED_FINGERPRINTS)?.toMutableSet() ?: mutableSetOf()
            if (verified) {
                current.add(fingerprint)
            } else {
                current.remove(fingerprint)
            }
            store.putStringSet(KEY_VERIFIED_FINGERPRINTS, current)
        }
    }

    fun getCachedPeerFingerprint(peerID: String): String? {
        val pid = peerID.lowercase()
        // Reading is safe without lock for SharedPreferences, but synchronizing ensures memory visibility
        // if we are paranoid, but SharedPreferences is generally thread-safe for reads.
        // However, to ensure we don't read a partial update (unlikely with SP), we can leave it.
        // The critical part is the write.
        val entries = store.getStringSet(KEY_CACHED_PEER_FINGERPRINTS) ?: return null
        val entry = entries.firstOrNull { it.startsWith("$pid:") } ?: return null
        return entry.substringAfter(':').takeIf { isValidFingerprint(it) }
    }

    fun cachePeerFingerprint(peerID: String, fingerprint: String) {
        if (!isValidFingerprint(fingerprint)) return
        val pid = peerID.lowercase()
        synchronized(lock) {
            val current = store.getStringSet(KEY_CACHED_PEER_FINGERPRINTS)?.toMutableSet() ?: mutableSetOf()
            current.removeAll { it.startsWith("$pid:") }
            current.add("$pid:$fingerprint")
            store.putStringSet(KEY_CACHED_PEER_FINGERPRINTS, current)
        }
    }

    fun getCachedNoiseKey(peerID: String): String? {
        val pid = peerID.lowercase()
        val entries = store.getStringSet(KEY_CACHED_PEER_NOISE_KEYS) ?: return null
        val entry = entries.firstOrNull { it.startsWith("$pid=") } ?: return null
        return entry.substringAfter('=').takeIf { it.matches(Regex("^[a-fA-F0-9]{64}$")) }
    }

    fun cachePeerNoiseKey(peerID: String, noiseKeyHex: String) {
        if (!noiseKeyHex.matches(Regex("^[a-fA-F0-9]{64}$"))) return
        val pid = peerID.lowercase()
        synchronized(lock) {
            val current = store.getStringSet(KEY_CACHED_PEER_NOISE_KEYS)?.toMutableSet() ?: mutableSetOf()
            current.removeAll { it.startsWith("$pid=") }
            current.add("$pid=${noiseKeyHex.lowercase()}")
            store.putStringSet(KEY_CACHED_PEER_NOISE_KEYS, current)
        }
    }

    fun getCachedNoiseFingerprint(noiseKeyHex: String): String? {
        val key = noiseKeyHex.lowercase()
        val entries = store.getStringSet(KEY_CACHED_NOISE_FINGERPRINTS) ?: return null
        val entry = entries.firstOrNull { it.startsWith("$key=") } ?: return null
        return entry.substringAfter('=').takeIf { isValidFingerprint(it) }
    }

    fun cacheNoiseFingerprint(noiseKeyHex: String, fingerprint: String) {
        if (!isValidFingerprint(fingerprint)) return
        if (!noiseKeyHex.matches(Regex("^[a-fA-F0-9]{64}$"))) return
        val key = noiseKeyHex.lowercase()
        synchronized(lock) {
            val current = store.getStringSet(KEY_CACHED_NOISE_FINGERPRINTS)?.toMutableSet() ?: mutableSetOf()
            current.removeAll { it.startsWith("$key=") }
            current.add("$key=$fingerprint")
            store.putStringSet(KEY_CACHED_NOISE_FINGERPRINTS, current)
        }
    }

    fun getCachedFingerprintNickname(fingerprint: String): String? {
        if (!isValidFingerprint(fingerprint)) return null
        val key = fingerprint.lowercase()
        val entries = store.getStringSet(KEY_CACHED_FINGERPRINT_NICKNAMES) ?: return null
        val entry = entries.firstOrNull { it.startsWith("$key=") } ?: return null
        val encoded = entry.substringAfter('=')
        return runCatching {
            val bytes = Base64.Default.decode(encoded)
            String(bytes, Charsets.UTF_8)
        }.getOrNull()
    }

    fun cacheFingerprintNickname(fingerprint: String, nickname: String) {
        if (!isValidFingerprint(fingerprint)) return
        val key = fingerprint.lowercase()
        val encoded = Base64.Default.encode(nickname.toByteArray(Charsets.UTF_8))
        synchronized(lock) {
            val current = store.getStringSet(KEY_CACHED_FINGERPRINT_NICKNAMES)?.toMutableSet() ?: mutableSetOf()
            current.removeAll { it.startsWith("$key=") }
            current.add("$key=$encoded")
            store.putStringSet(KEY_CACHED_FINGERPRINT_NICKNAMES, current)
        }
    }
    
    // MARK: - Peer ID Rotation Management (removed)
    // Android now derives peer ID from the persisted Noise identity fingerprint.
    // No timed peer ID rotation is performed here.
    
    // MARK: - Identity Validation
    
    /**
     * Validate that a public key is valid for Curve25519
     */
    fun validatePublicKey(publicKey: ByteArray): Boolean {
        if (publicKey.size != 32) return false
        
        // Check for all-zero key (invalid point)
        if (publicKey.all { it == 0.toByte() }) return false
        
        // Check for other known invalid points
        val invalidPoints = setOf(
            ByteArray(32) { 0x00.toByte() }, // All zeros
            ByteArray(32) { 0xFF.toByte() }, // All ones
            // Add other known invalid Curve25519 points if needed
        )
        
        return !invalidPoints.any { it.contentEquals(publicKey) }
    }
    
    /**
     * Validate that a private key is valid for Curve25519
     */
    fun validatePrivateKey(privateKey: ByteArray): Boolean {
        if (privateKey.size != 32) return false
        
        // Check for all-zero key
        if (privateKey.all { it == 0.toByte() }) return false
        
        // Check that clamping bits are correct for Curve25519
        val clampedKey = privateKey.clone()
        clampedKey[0] = (clampedKey[0].toInt() and 248).toByte()
        clampedKey[31] = (clampedKey[31].toInt() and 127).toByte()
        clampedKey[31] = (clampedKey[31].toInt() or 64).toByte()
        
        // After clamping, the key should not be all zeros
        return !clampedKey.all { it == 0.toByte() }
    }
    
    // MARK: - Debug Information
    
    /**
     * Get debug information about identity state
     */
    fun getDebugInfo(): String = buildString {
        appendLine("=== Identity State Manager Debug ===")
        
        val hasIdentity = store.contains(KEY_STATIC_PRIVATE_KEY)
        appendLine("Has identity: $hasIdentity")
        
        if (hasIdentity) {
            try {
                val keyPair = loadStaticKey()
                if (keyPair != null) {
                    val fingerprint = generateFingerprint(keyPair.second)
                    appendLine("Identity fingerprint: ${fingerprint.take(16)}...")
                    appendLine("Key validation: private=${validatePrivateKey(keyPair.first)}, public=${validatePublicKey(keyPair.second)}")
                }
            } catch (e: Exception) {
                appendLine("Key validation failed: ${e.message}")
            }
        }
    }
    
    // MARK: - Emergency Clear
    
    /**
     * Clear all identity data (for panic mode)
     */
    fun clearIdentityData() {
        try {
            store.clear()
            Log.w(TAG, "All identity data cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear identity data: ${e.message}")
        }
    }
    
    /**
     * Check if identity data exists
     */
    fun hasIdentityData(): Boolean {
        return store.contains(KEY_STATIC_PRIVATE_KEY) && store.contains(KEY_STATIC_PUBLIC_KEY)
    }
    
    // MARK: - Public SharedPreferences Access (for favorites and Nostr data)
    
    /**
     * Store a string value in secure preferences
     */
    fun storeSecureValue(key: String, value: String) {
        store.putString(key, value)
    }
    
    /**
     * Retrieve a string value from secure preferences
     */
    fun getSecureValue(key: String): String? {
        return store.getString(key)
    }
    
    /**
     * Remove a value from secure preferences
     */
    fun removeSecureValue(key: String) {
        store.remove(key)
    }
    
    /**
     * Check if a key exists in secure preferences
     */
    fun hasSecureValue(key: String): Boolean {
        return store.contains(key)
    }
    
    /**
     * Clear specific keys from secure preferences
     */
    fun clearSecureValues(vararg keys: String) {
        store.remove(*keys)
    }
}
