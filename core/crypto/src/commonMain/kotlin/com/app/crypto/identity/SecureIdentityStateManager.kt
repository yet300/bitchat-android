@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class, kotlin.time.ExperimentalTime::class)

package com.app.crypto.identity

import com.app.crypto.secure.SecureKeyValueStore
import com.app.crypto.hash.Sha256
import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import com.app.common.utils.Log
import com.app.common.encoding.hexEncodedString

/**
 * Manages persistent identity storage and peer ID rotation - 100% compatible with iOS implementation
 *
 * Handles:
 * - Static identity key persistence across app sessions
 * - Secure storage backed by an injected [SecureKeyValueStore] (KSafe-encrypted on Android)
 * - Fingerprint calculation and identity validation
 */
class SecureIdentityStateManager(private val store: SecureKeyValueStore) {
    
    companion object {
        private const val TAG = "SecureIdentityStateManager"
        private const val KEY_STATIC_PRIVATE_KEY = "static_private_key"
        private const val KEY_STATIC_PUBLIC_KEY = "static_public_key"
        private const val KEY_SIGNING_PRIVATE_KEY = "signing_private_key"
        private const val KEY_SIGNING_PUBLIC_KEY = "signing_public_key"
        private const val KEY_VERIFIED_FINGERPRINTS = "verified_fingerprints"
        private const val KEY_CACHED_PEER_FINGERPRINTS = "cached_peer_fingerprints"
        private const val KEY_CACHED_PEER_NOISE_KEYS = "cached_peer_noise_keys"
        private const val KEY_CACHED_NOISE_FINGERPRINTS = "cached_noise_fingerprints"
        private const val KEY_CACHED_FINGERPRINT_NICKNAMES = "cached_fingerprint_nicknames"
        private const val KEY_CACHED_FINGERPRINT_SIGNING_KEYS = "cached_fingerprint_signing_keys"
        private const val KEY_VERIFIED_AT = "verified_at"
        private const val KEY_VOUCH_BATCH_SENT_AT = "vouch_batch_sent_at"

        private val HEX_32_BYTES = Regex("^[a-fA-F0-9]{64}$")
    }
    
    private val lock = Lock()
    
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
                val privateKey = Base64.decode(privateKeyString)
                val publicKey = Base64.decode(publicKeyString)
                
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
            
            val privateKeyString = Base64.encode(privateKey)
            val publicKeyString = Base64.encode(publicKey)
            
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
                val privateKey = Base64.decode(privateKeyString)
                val publicKey = Base64.decode(publicKeyString)
                
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
            
            val privateKeyString = Base64.encode(privateKey)
            val publicKeyString = Base64.encode(publicKey)
            
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
        val hash = Sha256.digest(publicKeyData)
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
        setVerifiedFingerprint(fingerprint, verified, nowMs = Clock.System.now().toEpochMilliseconds())
    }

    /**
     * Records (or clears) verification of [fingerprint], stamping [nowMs] as the verification time.
     * The stamp orders outgoing vouch batches — see [mostRecentlyVerifiedFingerprints].
     */
    fun setVerifiedFingerprint(fingerprint: String, verified: Boolean, nowMs: Long) {
        if (!isValidFingerprint(fingerprint)) return
        lock.withLock {
            val current = store.getStringSet(KEY_VERIFIED_FINGERPRINTS)?.toMutableSet() ?: mutableSetOf()
            if (verified) {
                current.add(fingerprint)
            } else {
                current.remove(fingerprint)
            }
            store.putStringSet(KEY_VERIFIED_FINGERPRINTS, current)
            putTimestampLocked(KEY_VERIFIED_AT, fingerprint, if (verified) nowMs else null)
        }
    }

    /**
     * Verified fingerprints ordered most recently verified first, excluding [excluding]. Entries with
     * no recorded verification time sort last (they predate the stamp).
     */
    fun mostRecentlyVerifiedFingerprints(limit: Int, excluding: String? = null): List<String> {
        if (limit <= 0) return emptyList()
        val stamps = readTimestamps(KEY_VERIFIED_AT)
        return getVerifiedFingerprints()
            .asSequence()
            .filter { it != excluding }
            .sortedWith(compareByDescending<String> { stamps[it] ?: Long.MIN_VALUE }.thenBy { it })
            .take(limit)
            .toList()
    }

    // MARK: - Announce-bound Signing Keys (fingerprint -> Ed25519 public key)

    /**
     * The peer's announce-bound Ed25519 signing key. Required to verify anything that peer signed
     * (vouch attestations); persisted because a vouchee may be offline when we vouch for them.
     */
    fun getSigningPublicKey(fingerprint: String): ByteArray? {
        if (!isValidFingerprint(fingerprint)) return null
        val entries = store.getStringSet(KEY_CACHED_FINGERPRINT_SIGNING_KEYS) ?: return null
        val key = fingerprint.lowercase()
        val entry = entries.firstOrNull { it.startsWith("$key=") } ?: return null
        return entry.substringAfter('=').takeIf { it.matches(HEX_32_BYTES) }?.let { hex ->
            ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
    }

    fun cacheSigningPublicKey(fingerprint: String, signingPublicKey: ByteArray) {
        if (!isValidFingerprint(fingerprint) || signingPublicKey.size != 32) return
        val key = fingerprint.lowercase()
        val hex = signingPublicKey.hexEncodedString()
        lock.withLock {
            val current = store.getStringSet(KEY_CACHED_FINGERPRINT_SIGNING_KEYS)?.toMutableSet() ?: mutableSetOf()
            current.removeAll { it.startsWith("$key=") }
            current.add("$key=$hex")
            store.putStringSet(KEY_CACHED_FINGERPRINT_SIGNING_KEYS, current)
        }
    }

    // MARK: - Vouch Batch Rate Limiting

    /** When we last sent [fingerprint] a vouch batch, or null. Persisted across restarts. */
    fun lastVouchBatchSentAt(fingerprint: String): Long? =
        readTimestamps(KEY_VOUCH_BATCH_SENT_AT)[fingerprint.lowercase()]

    fun markVouchBatchSent(fingerprint: String, atMs: Long) {
        if (!isValidFingerprint(fingerprint)) return
        lock.withLock { putTimestampLocked(KEY_VOUCH_BATCH_SENT_AT, fingerprint, atMs) }
    }

    // MARK: - Timestamp Map Helpers

    private fun readTimestamps(storeKey: String): Map<String, Long> {
        val entries = store.getStringSet(storeKey) ?: return emptyMap()
        return entries.mapNotNull { entry ->
            val name = entry.substringBefore('=', missingDelimiterValue = "")
            val millis = entry.substringAfter('=', missingDelimiterValue = "").toLongOrNull()
            if (name.isEmpty() || millis == null) null else name to millis
        }.toMap()
    }

    /** Requires [lock]. A null [millis] removes the entry. */
    private fun putTimestampLocked(storeKey: String, fingerprint: String, millis: Long?) {
        val key = fingerprint.lowercase()
        val current = store.getStringSet(storeKey)?.toMutableSet() ?: mutableSetOf()
        current.removeAll { it.startsWith("$key=") }
        if (millis != null) current.add("$key=$millis")
        store.putStringSet(storeKey, current)
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
        lock.withLock {
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
        lock.withLock {
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
        lock.withLock {
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
            val bytes = Base64.decode(encoded)
            bytes.decodeToString()
        }.getOrNull()
    }

    fun cacheFingerprintNickname(fingerprint: String, nickname: String) {
        if (!isValidFingerprint(fingerprint)) return
        val key = fingerprint.lowercase()
        val encoded = Base64.encode(nickname.encodeToByteArray())
        lock.withLock {
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
        val clampedKey = privateKey.copyOf()
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
     * Clear all identity data (for panic mode). Suspends — wipes the whole encrypted store.
     */
    suspend fun clearIdentityData() {
        try {
            store.clear()
            Log.w(TAG, "All identity data cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear identity data: ${e.message}")
        }
    }

    /**
     * Synchronously remove this manager's own identity keys (Noise static, Ed25519 signing, verified
     * and cached fingerprint sets). Non-blocking — used where a full async store wipe cannot suspend
     * and only the identity keys must be dropped before in-memory regeneration.
     */
    fun clearIdentityKeysImmediate() {
        store.remove(
            KEY_STATIC_PRIVATE_KEY,
            KEY_STATIC_PUBLIC_KEY,
            KEY_SIGNING_PRIVATE_KEY,
            KEY_SIGNING_PUBLIC_KEY,
            KEY_VERIFIED_FINGERPRINTS,
            KEY_CACHED_PEER_FINGERPRINTS,
            KEY_CACHED_PEER_NOISE_KEYS,
            KEY_CACHED_NOISE_FINGERPRINTS,
            KEY_CACHED_FINGERPRINT_NICKNAMES,
            KEY_CACHED_FINGERPRINT_SIGNING_KEYS,
            KEY_VERIFIED_AT,
            KEY_VOUCH_BATCH_SENT_AT,
        )
        Log.w(TAG, "Identity keys cleared (immediate)")
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
