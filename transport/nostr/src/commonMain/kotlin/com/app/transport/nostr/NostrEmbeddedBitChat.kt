@file:OptIn(ExperimentalTime::class, ExperimentalEncodingApi::class)

package com.app.transport.nostr

import com.app.common.encoding.hexEncodedString
import com.app.common.utils.Log
import com.app.transport.MeshConstants
import com.app.transport.model.NoisePayload
import com.app.transport.model.PrivateMessagePacket
import com.app.transport.model.NoisePayloadType
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * BitChat-over-Nostr Adapter
 * Direct port from iOS implementation for 100% compatibility
 */
object NostrEmbeddedBitChat {

    private const val TAG = "NostrEmbeddedBitChat"

    /**
     * A decoded `bitchat1:` embedded packet recovered from an incoming Nostr rumor — the inverse of
     * [encodePMForNostr]/[encodeAckForNostr]. [messageID]/[content] are populated for
     * PRIVATE_MESSAGE; [messageID] alone for DELIVERED/READ_RECEIPT acks.
     */
    data class Embedded(
        val senderPeerID: String,
        val type: NoisePayloadType,
        val payload: NoisePayload,
        val messageID: String?,
        val content: String?,
    )

    /**
     * Decode a `bitchat1:` base64url string back into its embedded BitChat packet. Inverse of the
     * `encode*` builders: unwrap base64url -> [BitchatPacket] -> [NoisePayload], then recover the
     * private-message / ack fields. Returns null for anything that is not a NOISE_ENCRYPTED packet.
     */
    fun decode(bitchat1: String): Embedded? {
        try {
            if (!bitchat1.startsWith("bitchat1:")) return null
            val packetData = base64URLDecode(bitchat1.removePrefix("bitchat1:")) ?: return null
            val packet = BitchatPacket.fromBinaryData(packetData) ?: return null
            if (packet.type != MessageType.NOISE_ENCRYPTED.value) return null
            val payload = NoisePayload.decode(packet.payload) ?: return null
            val senderPeerID = packet.senderID.hexEncodedString()
            val messageID: String?
            val content: String?
            when (payload.type) {
                NoisePayloadType.PRIVATE_MESSAGE -> {
                    val pm = PrivateMessagePacket.decode(payload.data)
                    messageID = pm?.messageID
                    content = pm?.content
                }
                NoisePayloadType.DELIVERED, NoisePayloadType.READ_RECEIPT -> {
                    messageID = payload.data.decodeToString()
                    content = null
                }
                else -> {
                    messageID = null
                    content = null
                }
            }
            return Embedded(senderPeerID, payload.type, payload, messageID, content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode embedded BitChat packet: ${e.message}")
            return null
        }
    }

    private fun base64URLDecode(input: String): ByteArray? {
        return try {
            val padded = input.replace("-", "+").replace("_", "/").let { str ->
                val padding = (4 - str.length % 4) % 4
                str + "=".repeat(padding)
            }
            Base64.decode(padded)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode base64url: ${e.message}")
            null
        }
    }
    
    /**
     * Build a `bitchat1:` base64url-encoded BitChat packet carrying a private message for Nostr DMs.
     */
    fun encodePMForNostr(
        content: String,
        messageID: String,
        recipientPeerID: String,
        senderPeerID: String
    ): String? {
        try {
            // TLV-encode the private message
            val pm = PrivateMessagePacket(messageID = messageID, content = content)
            val tlv = pm.encode() ?: return null
            
            // Prefix with NoisePayloadType
            val payload = ByteArray(1 + tlv.size)
            payload[0] = NoisePayloadType.PRIVATE_MESSAGE.value.toByte()
            tlv.copyInto(payload, destinationOffset = 1)
            
            // Determine 8-byte recipient ID to embed
            val recipientIDHex = normalizeRecipientPeerID(recipientPeerID)
            
            val packet = BitchatPacket(
                version = 1u,
                type = MessageType.NOISE_ENCRYPTED.value,
                senderID = hexStringToByteArray(senderPeerID),
                recipientID = hexStringToByteArray(recipientIDHex),
                timestamp = Clock.System.now().toEpochMilliseconds().toULong(),
                payload = payload,
                signature = null,
                ttl = MeshConstants.MESSAGE_TTL_HOPS
            )
            
            val data = packet.toBinaryData() ?: return null
            return "bitchat1:" + base64URLEncode(data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode PM for Nostr: ${e.message}")
            return null
        }
    }
    
    /**
     * Build a `bitchat1:` base64url-encoded BitChat packet carrying a delivery/read ack for Nostr DMs.
     */
    fun encodeAckForNostr(
        type: NoisePayloadType,
        messageID: String,
        recipientPeerID: String,
        senderPeerID: String
    ): String? {
        if (type != NoisePayloadType.DELIVERED && type != NoisePayloadType.READ_RECEIPT) {
            return null
        }
        
        try {
            val messageIDBytes = messageID.encodeToByteArray()
            val payload = ByteArray(1 + messageIDBytes.size)
            payload[0] = type.value.toByte()
            messageIDBytes.copyInto(payload, destinationOffset = 1)
            
            val recipientIDHex = normalizeRecipientPeerID(recipientPeerID)
            
            val packet = BitchatPacket(
                version = 1u,
                type = MessageType.NOISE_ENCRYPTED.value,
                senderID = hexStringToByteArray(senderPeerID),
                recipientID = hexStringToByteArray(recipientIDHex),
                timestamp = Clock.System.now().toEpochMilliseconds().toULong(),
                payload = payload,
                signature = null,
                ttl = MeshConstants.MESSAGE_TTL_HOPS
            )
            
            val data = packet.toBinaryData() ?: return null
            return "bitchat1:" + base64URLEncode(data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode ACK for Nostr: ${e.message}")
            return null
        }
    }
    
    /**
     * Build a `bitchat1:` ACK (delivered/read) without an embedded recipient peer ID (geohash DMs).
     */
    fun encodeAckForNostrNoRecipient(
        type: NoisePayloadType,
        messageID: String,
        senderPeerID: String
    ): String? {
        if (type != NoisePayloadType.DELIVERED && type != NoisePayloadType.READ_RECEIPT) {
            return null
        }
        
        try {
            val messageIDBytes = messageID.encodeToByteArray()
            val payload = ByteArray(1 + messageIDBytes.size)
            payload[0] = type.value.toByte()
            messageIDBytes.copyInto(payload, destinationOffset = 1)
            
            val packet = BitchatPacket(
                version = 1u,
                type = MessageType.NOISE_ENCRYPTED.value,
                senderID = hexStringToByteArray(senderPeerID),
                recipientID = null, // No recipient for geohash DMs
                timestamp = Clock.System.now().toEpochMilliseconds().toULong(),
                payload = payload,
                signature = null,
                ttl = MeshConstants.MESSAGE_TTL_HOPS
            )
            
            val data = packet.toBinaryData() ?: return null
            return "bitchat1:" + base64URLEncode(data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode ACK for Nostr (no recipient): ${e.message}")
            return null
        }
    }
    
    /**
     * Build a `bitchat1:` payload without an embedded recipient peer ID (used for geohash DMs).
     */
    fun encodePMForNostrNoRecipient(
        content: String,
        messageID: String,
        senderPeerID: String
    ): String? {
        try {
            val pm = PrivateMessagePacket(messageID = messageID, content = content)
            val tlv = pm.encode() ?: return null
            
            val payload = ByteArray(1 + tlv.size)
            payload[0] = NoisePayloadType.PRIVATE_MESSAGE.value.toByte()
            tlv.copyInto(payload, destinationOffset = 1)
            
            val packet = BitchatPacket(
                version = 1u,
                type = MessageType.NOISE_ENCRYPTED.value,
                senderID = hexStringToByteArray(senderPeerID),
                recipientID = null, // No recipient for geohash DMs
                timestamp = Clock.System.now().toEpochMilliseconds().toULong(),
                payload = payload,
                signature = null,
                ttl = MeshConstants.MESSAGE_TTL_HOPS
            )
            
            val data = packet.toBinaryData() ?: return null
            return "bitchat1:" + base64URLEncode(data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode PM for Nostr (no recipient): ${e.message}")
            return null
        }
    }
    
    /**
     * Normalize recipient peer ID (matches iOS implementation)
     */
    private fun normalizeRecipientPeerID(recipientPeerID: String): String {
        try {
            val maybeData = hexStringToByteArray(recipientPeerID)
            return when (maybeData.size) {
                32 -> {
                    // Treat as Noise static public key; derive peerID from fingerprint
                    // For now, return first 8 bytes as hex (simplified)
                    maybeData.take(8).toByteArray().hexEncodedString()
                }
                8 -> {
                    // Already an 8-byte peer ID
                    recipientPeerID
                }
                else -> {
                    // Fallback: return as-is (expecting 16 hex chars)
                    recipientPeerID
                }
            }
        } catch (e: Exception) {
            // Fallback: return as-is
            return recipientPeerID
        }
    }
    
    /**
     * Base64url encode without padding (matches iOS implementation)
     */
    private fun base64URLEncode(data: ByteArray): String {
        val b64 = Base64.encode(data)
        return b64
            .replace("+", "-")
            .replace("/", "_")
            .replace("=", "")
    }
    
    /**
     * Convert hex string to byte array.
     * Deliberately NOT the shared peerIdToRoutingBytes: this copy rejects odd-length
     * input up-front (all-zero ID) while the shared helper zero-fills per-pair.
     * Unifying the malformed-input policy is an owner decision (arch review L2).
     */
    private fun hexStringToByteArray(hexString: String): ByteArray {
        if (hexString.length % 2 != 0) {
            return ByteArray(8) // Return 8-byte array filled with zeros
        }
        
        val result = ByteArray(8) { 0 } // Exactly 8 bytes like iOS
        var tempID = hexString
        var index = 0
        
        while (tempID.length >= 2 && index < 8) {
            val hexByte = tempID.substring(0, 2)
            val byte = hexByte.toIntOrNull(16)?.toByte()
            if (byte != null) {
                result[index] = byte
            }
            tempID = tempID.substring(2)
            index++
        }
        
        return result
    }
}
