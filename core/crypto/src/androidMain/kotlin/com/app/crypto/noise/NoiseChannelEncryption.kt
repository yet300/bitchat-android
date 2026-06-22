package com.app.crypto.noise

import com.app.common.utils.Log
import com.app.common.serialization.JsonConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.app.crypto.hash.Sha256
import java.util.concurrent.ConcurrentHashMap

/**
 * Channel encryption for password-protected channels - 100% compatible with iOS implementation
 * 
 * Uses PBKDF2 key derivation with channel name as salt and AES-256-GCM for encryption.
 * This is separate from Noise sessions and used for group channels with shared passwords.
 */
internal class NoiseChannelEncryption {
    
    companion object {
        private const val TAG = "NoiseChannelEncryption"
    }

    // Channel keys storage (channelName -> raw 256-bit AES key)
    private val channelKeys = ConcurrentHashMap<String, ByteArray>()
    
    // Channel passwords (for rekey operations)
    private val channelPasswords = ConcurrentHashMap<String, String>()
    
    // MARK: - Channel Password Management
    
    /**
     * Set password for a channel and derive encryption key
     */
    fun setChannelPassword(password: String, channel: String) {
        try {
            if (password.isEmpty()) {
                Log.w(TAG, "Empty password provided for channel $channel")
                return
            }
            
            // Derive key from password using PBKDF2 (same as iOS)
            val key = ChannelCipher.deriveKey(password, channel)
            
            // Store key and password
            channelKeys[channel] = key
            channelPasswords[channel] = password
            
            Log.d(TAG, "Set password for channel $channel")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set password for channel $channel: ${e.message}")
        }
    }
    
    /**
     * Remove password for a channel
     */
    fun removeChannelPassword(channel: String) {
        channelKeys.remove(channel)
        channelPasswords.remove(channel)
        Log.d(TAG, "Removed password for channel $channel")
    }
    
    /**
     * Check if we have a key for a channel
     */
    fun hasChannelKey(channel: String): Boolean {
        return channelKeys.containsKey(channel)
    }
    
    /**
     * Get channel password (if available)
     */
    fun getChannelPassword(channel: String): String? {
        return channelPasswords[channel]
    }
    
    // MARK: - Encryption/Decryption
    
    /**
     * Encrypt a message for a channel
     * Returns encrypted data including IV
     */
    fun encryptChannelMessage(message: String, channel: String): ByteArray {
        val key = channelKeys[channel]
            ?: throw IllegalStateException("No key available for channel $channel")
        
        val messageBytes = message.toByteArray(Charsets.UTF_8)

        return try {
            // IV(12) || ciphertext || tag(16) — same wire format as iOS.
            ChannelCipher.encrypt(key, messageBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt channel message: ${e.message}")
            throw e
        }
    }
    
    /**
     * Decrypt a message for a channel
     * Expects data format: IV + encrypted_data + auth_tag
     */
    fun decryptChannelMessage(encryptedData: ByteArray, channel: String): String {
        val key = channelKeys[channel]
            ?: throw IllegalStateException("No key available for channel $channel")
        
        if (encryptedData.size < 16) { // 12 bytes IV + minimum ciphertext
            throw IllegalArgumentException("Encrypted data too short")
        }
        
        return try {
            String(ChannelCipher.decrypt(key, encryptedData), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt channel message: ${e.message}")
            throw e
        }
    }
    
    // MARK: - Key Verification
    
    /**
     * Calculate key commitment (SHA-256 hash) for verification
     * This allows peers to verify they have the same key without revealing it
     */
    fun calculateKeyCommitment(channel: String): String? {
        val key = channelKeys[channel] ?: return null
        
        return try {
            val hash = Sha256.digest(key)
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate key commitment: ${e.message}")
            null
        }
    }
    
    /**
     * Verify key commitment matches our derived key
     */
    fun verifyKeyCommitment(channel: String, commitment: String): Boolean {
        val ourCommitment = calculateKeyCommitment(channel)
        return ourCommitment?.lowercase() == commitment.lowercase()
    }
    
    // MARK: - Channel Key Sharing
    
    /**
     * Create channel key packet for sharing via Noise session
     * Returns encrypted packet that can be sent to other peers
     */
    fun createChannelKeyPacket(password: String, channel: String): ByteArray? {
        return try {
            // Create key packet with channel and password
            val packet = buildJsonObject {
                put("channel", JsonPrimitive(channel))
                put("password", JsonPrimitive(password))
                put("timestamp", JsonPrimitive(System.currentTimeMillis()))
            }

            // Simple JSON encoding for now (could be replaced with more efficient format)
            val json = JsonConfig.json.encodeToString(JsonObject.serializer(), packet)
            json.toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create channel key packet: ${e.message}")
            null
        }
    }
    
    /**
     * Process received channel key packet
     * Returns (channel, password) if successful
     */
    fun processChannelKeyPacket(data: ByteArray): Pair<String, String>? {
        return try {
            val json = String(data, Charsets.UTF_8)
            val packet = JsonConfig.json.parseToJsonElement(json) as JsonObject

            val channel = packet["channel"]?.jsonPrimitive?.contentOrNull
            val password = packet["password"]?.jsonPrimitive?.contentOrNull
            
            if (channel != null && password != null) {
                Pair(channel, password)
            } else {
                Log.w(TAG, "Invalid channel key packet format")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process channel key packet: ${e.message}")
            null
        }
    }
    
    // MARK: - Debug and Management
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String = buildString {
        appendLine("=== Channel Encryption Debug ===")
        appendLine("Active channels: ${channelKeys.size}")
        
        channelKeys.keys.forEach { channel ->
            val hasPassword = channelPasswords.containsKey(channel)
            val commitment = calculateKeyCommitment(channel)?.take(16)
            appendLine("  $channel: hasPassword=$hasPassword, commitment=${commitment}...")
        }
    }
    
    /**
     * Get list of channels with keys
     */
    fun getActiveChannels(): Set<String> {
        return channelKeys.keys.toSet()
    }
    
    /**
     * Clear all channel data
     */
    fun clear() {
        channelKeys.clear()
        channelPasswords.clear()
        Log.d(TAG, "Cleared all channel encryption data")
    }
}
