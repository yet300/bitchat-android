@file:OptIn(ExperimentalTime::class)

package com.app.transport.protocol

import com.app.common.utils.Log
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Message types - exact same as iOS version with Noise Protocol support
 */
enum class MessageType(val value: UByte) {
    ANNOUNCE(0x01u),
    MESSAGE(0x02u),  // All user messages (private and broadcast)
    LEAVE(0x03u),
    COURIER_ENVELOPE(0x04u), // Store-and-forward envelope carried by a trusted peer (CourierEnvelope)
    NOISE_HANDSHAKE(0x10u),  // Noise handshake
    NOISE_ENCRYPTED(0x11u),  // Noise encrypted transport message
    FRAGMENT(0x20u), // Fragmentation for large packets
    REQUEST_SYNC(0x21u), // GCS-based sync request
    FILE_TRANSFER(0x22u), // New: File transfer packet (BLE voice notes, etc.)
    BOARD_POST(0x23u), // Signed geohash bulletin-board post or tombstone (BoardWire)
    PREKEY_BUNDLE(0x24u), // Signed one-time prekey bundle broadcast for forward-secret courier sealing (PrekeyBundle)
    GROUP_MESSAGE(0x25u), // Group-encrypted broadcast: cleartext group ID + ChaChaPoly body (GroupMessageEnvelope)

    // Mesh diagnostics: directed, unsigned, unencrypted echo probe/reply (MeshPingPayload).
    PING(0x26u),
    PONG(0x27u),

    // Complete signed Nostr event ferried between mesh-only and internet gateway peers.
    NOSTR_CARRIER(0x28u);

    companion object {
        fun fromValue(value: UByte): MessageType? {
            return entries.find { it.value == value }
        }
    }
}

/**
 * Special recipient IDs - exact same as iOS version
 */
object SpecialRecipients {
    val BROADCAST = ByteArray(8) { 0xFF.toByte() }  // All 0xFF = broadcast
}

/**
 * Binary packet format - 100% backward compatible with iOS version
 *
 * Header (14 bytes for v1, 16 bytes for v2):
 * - Version: 1 byte
 * - Type: 1 byte
 * - TTL: 1 byte
 * - Timestamp: 8 bytes (UInt64, big-endian)
 * - Flags: 1 byte (bit 0: hasRecipient, bit 1: hasSignature, bit 2: isCompressed)
 * - PayloadLength: 2 bytes (v1) / 4 bytes (v2) (big-endian)
 *
 * Variable sections:
 * - SenderID: 8 bytes (fixed)
 * - RecipientID: 8 bytes (if hasRecipient flag set)
 * - Payload: Variable length (includes original size if compressed)
 * - Signature: 64 bytes (if hasSignature flag set)
 */
@Serializable
data class BitchatPacket(
    val version: UByte = 1u,
    val type: UByte,
    val senderID: ByteArray,
    val recipientID: ByteArray? = null,
    val timestamp: ULong,
    val payload: ByteArray,
    var signature: ByteArray? = null,  // Changed from val to var for packet signing
    var ttl: UByte,
    var route: List<ByteArray>? = null // Optional source route: ordered list of peerIDs (8 bytes each), not including sender and final recipient
) {

    constructor(
        type: UByte,
        ttl: UByte,
        senderID: String,
        payload: ByteArray
    ) : this(
        version = 1u,
        type = type,
        senderID = peerIdToRoutingBytes(senderID),
        recipientID = null,
        timestamp = (Clock.System.now().toEpochMilliseconds()).toULong(),
        payload = payload,
        signature = null,
        ttl = ttl
    )

    fun toBinaryData(padding: Boolean = true): ByteArray? {
        return BinaryProtocol.encode(this, padding = padding)
    }

    /**
     * Create binary representation for signing (without signature and TTL fields)
     * TTL is excluded because it changes during packet relay operations
     */
    fun toBinaryDataForSigning(): ByteArray? {
        // Create a copy without signature and with fixed TTL for signing
        // TTL must be excluded because it changes during relay
        val unsignedPacket = BitchatPacket(
            version = version,
            type = type,
            senderID = senderID,
            recipientID = recipientID,
            timestamp = timestamp,
            payload = payload,
            signature = null, // Remove signature for signing
            route = route,
            ttl = 0u.toUByte() // Use fixed TTL=0 for signing to ensure relay compatibility
        )
        return BinaryProtocol.encode(unsignedPacket)
    }

    companion object {
        fun fromBinaryData(data: ByteArray): BitchatPacket? {
            return BinaryProtocol.decode(data)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as BitchatPacket

        if (version != other.version) return false
        if (type != other.type) return false
        if (!senderID.contentEquals(other.senderID)) return false
        if (recipientID != null) {
            if (other.recipientID == null) return false
            if (!recipientID.contentEquals(other.recipientID)) return false
        } else if (other.recipientID != null) return false
        if (timestamp != other.timestamp) return false
        if (!payload.contentEquals(other.payload)) return false
        if (signature != null) {
            if (other.signature == null) return false
            if (!signature.contentEquals(other.signature)) return false
        } else if (other.signature != null) return false
        if (ttl != other.ttl) return false
        if (route != null || other.route != null) {
            val a = route?.map { it.toList() } ?: emptyList()
            val b = other.route?.map { it.toList() } ?: emptyList()
            if (a != b) return false
        }

        return true
    }

    override fun hashCode(): Int {
        var result = version.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + senderID.contentHashCode()
        result = 31 * result + (recipientID?.contentHashCode() ?: 0)
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        result = 31 * result + ttl.hashCode()
        result = 31 * result + (route?.fold(1) { acc, bytes -> 31 * acc + bytes.contentHashCode() } ?: 0)
        return result
    }
}

/**
 * Binary Protocol implementation - supports v1 and v2, backward compatible
 */
object BinaryProtocol {
    private const val HEADER_SIZE_V1 = 14
    private const val HEADER_SIZE_V2 = 16
    private const val SENDER_ID_SIZE = 8
    private const val RECIPIENT_ID_SIZE = 8
    private const val SIGNATURE_SIZE = 64
    private const val MAX_PAYLOAD_LENGTH = 10_485_760  // 10 MiB — wire-level payload cap, same as iOS

    object Flags {
        const val HAS_RECIPIENT: UByte = 0x01u
        const val HAS_SIGNATURE: UByte = 0x02u
        const val IS_COMPRESSED: UByte = 0x04u
        const val HAS_ROUTE: UByte = 0x08u
    }

    private fun getHeaderSize(version: UByte): Int {
        return when (version) {
            1u.toUByte() -> HEADER_SIZE_V1
            else -> HEADER_SIZE_V2  // v2+ will use 4-byte payload length
        }
    }
    
    fun encode(packet: BitchatPacket, padding: Boolean = true): ByteArray? {
        try {
            // Fail loudly on inputs the wire format cannot represent — a silently
            // truncated frame would be misparsed by the decoder on the other side.
            packet.signature?.let { signature ->
                if (signature.size != SIGNATURE_SIZE) {
                    Log.e(
                        "BinaryProtocol",
                        "Refusing to encode packet type ${packet.type}: signature is ${signature.size} bytes, expected $SIGNATURE_SIZE",
                    )
                    return null
                }
            }
            if (packet.version == 1u.toUByte() && packet.payload.size > 0xFFFF) {
                Log.e(
                    "BinaryProtocol",
                    "Refusing to encode v1 packet type ${packet.type}: payload ${packet.payload.size} bytes exceeds the 65535-byte v1 limit",
                )
                return null
            }

            // Try to compress payload if beneficial
            var payload = packet.payload
            var originalPayloadSize: Int? = null
            var isCompressed = false
            
            if (CompressionUtil.shouldCompress(payload)) {
                CompressionUtil.compress(payload)?.let { compressedPayload ->
                    originalPayloadSize = payload.size
                    payload = compressedPayload
                    isCompressed = true
                }
            }
            
            // Size of the compressed-original-size prefix (only present when isCompressed).
            val sizeFieldBytes = if (isCompressed) (if (packet.version >= 2u.toUByte()) 4 else 2) else 0
            // kotlinx-io Buffer grows as needed and writes big-endian by default (matches the iOS
            // wire, BIG_ENDIAN). The old explicit capacity math is no longer needed.
            val buffer = Buffer()

            // Header
            buffer.writeByte(packet.version.toByte())
            buffer.writeByte(packet.type.toByte())
            buffer.writeByte(packet.ttl.toByte())

            // Timestamp (8 bytes, big-endian)
            buffer.writeLong(packet.timestamp.toLong())
            
            // Flags
            var flags: UByte = 0u
            if (packet.recipientID != null) {
                flags = flags or Flags.HAS_RECIPIENT
            }
            if (packet.signature != null) {
                flags = flags or Flags.HAS_SIGNATURE
            }
            if (isCompressed) {
                flags = flags or Flags.IS_COMPRESSED
            }
            // HAS_ROUTE is only supported for v2+ packets
            if (!packet.route.isNullOrEmpty() && packet.version >= 2u.toUByte()) {
                flags = flags or Flags.HAS_ROUTE
            }
            buffer.writeByte(flags.toByte())

            // Payload length (2 or 4 bytes, big-endian) - includes original size if compressed
            val payloadDataSize = payload.size + sizeFieldBytes
            if (packet.version >= 2u.toUByte()) {
                buffer.writeInt(payloadDataSize)  // 4 bytes for v2+
            } else {
                if (payloadDataSize > 0xFFFF) {
                    // Edge case: compressed payload + size prefix overflows the v1 field
                    Log.e("BinaryProtocol", "Refusing to encode v1 packet type ${packet.type}: payload field $payloadDataSize exceeds 65535")
                    return null
                }
                buffer.writeShort(payloadDataSize.toShort())  // 2 bytes for v1
            }

            // SenderID (exactly 8 bytes)
            val senderBytes = packet.senderID.take(SENDER_ID_SIZE).toByteArray()
            buffer.write(senderBytes)
            if (senderBytes.size < SENDER_ID_SIZE) {
                buffer.write(ByteArray(SENDER_ID_SIZE - senderBytes.size))
            }

            // RecipientID (if present)
            packet.recipientID?.let { recipientID ->
                val recipientBytes = recipientID.take(RECIPIENT_ID_SIZE).toByteArray()
                buffer.write(recipientBytes)
                if (recipientBytes.size < RECIPIENT_ID_SIZE) {
                    buffer.write(ByteArray(RECIPIENT_ID_SIZE - recipientBytes.size))
                }
            }

            // Route (optional, v2+ only): 1 byte count + N*8 bytes
            if (packet.version >= 2u.toUByte() && !packet.route.isNullOrEmpty()) {
                packet.route?.let { routeList ->
                    val cleaned = routeList.map { bytes -> bytes.take(SENDER_ID_SIZE).toByteArray().let { if (it.size < SENDER_ID_SIZE) it + ByteArray(SENDER_ID_SIZE - it.size) else it } }
                    val count = cleaned.size.coerceAtMost(255)
                    buffer.writeByte(count.toByte())
                    cleaned.take(count).forEach { hop -> buffer.write(hop) }
                }
            }

            // Payload (with original size prepended if compressed)
            if (isCompressed) {
                val originalSize = originalPayloadSize
                if (originalSize != null) {
                    if (packet.version >= 2u.toUByte()) {
                        buffer.writeInt(originalSize)
                    } else {
                        buffer.writeShort(originalSize.toShort())
                    }
                }
            }
            buffer.write(payload)

            // Signature (if present)
            packet.signature?.let { signature ->
                buffer.write(signature.take(SIGNATURE_SIZE).toByteArray())
            }

            val result = buffer.readByteArray()
            
            // Apply padding only when requested. iOS pads just Noise frames over BLE
            // (see BLEPacketPaddingPolicy); public frames go out unpadded for wire parity.
            if (padding) {
                val optimalSize = MessagePadding.optimalBlockSize(result.size)
                return MessagePadding.pad(result, optimalSize)
            }

            return result

        } catch (e: Exception) {
            Log.e("BinaryProtocol", "Error encoding packet type ${packet.type}: ${e.message}")
            return null
        }
    }
    
    fun decode(data: ByteArray): BitchatPacket? {
        // Try decode as-is first (robust when padding wasn't applied) - iOS fix
        decodeCore(data)?.let { return it }
        
        // If that fails, try after removing padding
        val unpadded = MessagePadding.unpad(data)
        if (unpadded.contentEquals(data)) return null // No padding was removed, already failed
        
        return decodeCore(unpadded)
    }
    
    /**
     * Core decoding implementation used by decode() with and without padding removal - iOS fix
     */
    private fun decodeCore(raw: ByteArray): BitchatPacket? {
        try {
            if (raw.size < HEADER_SIZE_V1 + SENDER_ID_SIZE) return null

            // kotlinx-io Buffer reads big-endian by default (matches the iOS wire, BIG_ENDIAN).
            val buffer = Buffer().apply { write(raw) }

            // Header
            val version = buffer.readByte().toUByte()
            if (version.toUInt() != 1u && version.toUInt() != 2u) return null  // Support v1 and v2

            val headerSize = getHeaderSize(version)

            val type = buffer.readByte().toUByte()
            val ttl = buffer.readByte().toUByte()

            // Timestamp
            val timestamp = buffer.readLong().toULong()

            // Flags
            val flags = buffer.readByte().toUByte()
            val hasRecipient = (flags and Flags.HAS_RECIPIENT) != 0u.toUByte()
            val hasSignature = (flags and Flags.HAS_SIGNATURE) != 0u.toUByte()
            val isCompressed = (flags and Flags.IS_COMPRESSED) != 0u.toUByte()
            // HAS_ROUTE is only valid for v2+ packets; ignore the flag for v1
            val hasRoute = (version >= 2u.toUByte()) && (flags and Flags.HAS_ROUTE) != 0u.toUByte()

            // Payload length - version-dependent (2 or 4 bytes)
            val payloadLength = if (version >= 2u.toUByte()) {
                buffer.readInt().toUInt()  // 4 bytes for v2+
            } else {
                buffer.readShort().toUShort().toUInt()  // 2 bytes for v1, convert to UInt
            }

            if (payloadLength.toLong() > MAX_PAYLOAD_LENGTH.toLong()) {
                Log.w("BinaryProtocol", "Payload length $payloadLength exceeds maximum allowed (${MAX_PAYLOAD_LENGTH})")
                return null
            }

            var expectedSize = headerSize + SENDER_ID_SIZE + payloadLength.toInt()
            if (hasRecipient) expectedSize += RECIPIENT_ID_SIZE
            var routeCount = 0
            if (hasRoute) {
                // Peek count (1 byte) without consuming the stream by indexing the raw array.
                // Exactly the fixed header has been read so far, so the cursor is at headerSize
                // (the start of SenderID). Skip SenderID and RecipientID (if present) to the count.
                val currentPos = headerSize
                var routeOffset = currentPos + SENDER_ID_SIZE
                if (hasRecipient) {
                    routeOffset += RECIPIENT_ID_SIZE
                }

                if (raw.size >= routeOffset + 1) {
                    routeCount = raw[routeOffset].toUByte().toInt()
                }
                expectedSize += 1 + (routeCount * SENDER_ID_SIZE)
            }
            if (hasSignature) expectedSize += SIGNATURE_SIZE

            if (raw.size < expectedSize) return null
            
            // SenderID
            val senderID = buffer.readByteArray(SENDER_ID_SIZE)

            // RecipientID
            val recipientID = if (hasRecipient) {
                buffer.readByteArray(RECIPIENT_ID_SIZE)
            } else null

            // Route (optional)
            val route: List<ByteArray>? = if (hasRoute) {
                val count = buffer.readByte().toUByte().toInt()
                if (count == 0) {
                    null // Treat empty route list as null to enforce canonical representation
                } else {
                    val hops = mutableListOf<ByteArray>()
                    repeat(count) {
                        hops.add(buffer.readByteArray(SENDER_ID_SIZE))
                    }
                    hops
                }
            } else null

            // Payload
            val payload = if (isCompressed) {
                val lengthFieldBytes = if (version >= 2u.toUByte()) 4 else 2
                if (payloadLength.toInt() < lengthFieldBytes) return null

                val originalSize = if (version >= 2u.toUByte()) {
                    buffer.readInt()
                } else {
                    buffer.readShort().toUShort().toInt()
                }

                // Security check: the claimed plain size is attacker-controlled and becomes
                // the decompression allocation bound — reject before inflating anything.
                if (originalSize !in 1..MAX_PAYLOAD_LENGTH) {
                    Log.w("BinaryProtocol", "🚫 Claimed decompressed size $originalSize out of bounds")
                    return null
                }

                // Compressed payload
                val compressedSize = payloadLength.toInt() - lengthFieldBytes
                val compressedPayload = buffer.readByteArray(compressedSize)

                // Security check: Compression bomb protection
                if (compressedSize > 0) {
                    val ratio = originalSize.toDouble() / compressedSize.toDouble()
                    if (ratio > 50_000.0) {
                        Log.w("BinaryProtocol", "🚫 Suspicious compression ratio: ${ratio}:1")
                        return null
                    }
                }
                
                // Decompress
                CompressionUtil.decompress(compressedPayload, originalSize) ?: return null
            } else {
                buffer.readByteArray(payloadLength.toInt())
            }

            // Signature
            val signature = if (hasSignature) {
                buffer.readByteArray(SIGNATURE_SIZE)
            } else null
            
            return BitchatPacket(
                version = version,
                type = type,
                senderID = senderID,
                recipientID = recipientID,
                timestamp = timestamp,
                payload = payload,
                signature = signature,
                ttl = ttl,
                route = route
            )
            
        } catch (e: Throwable) {
            Log.e("BinaryProtocol", "Error decoding packet: ${e.message}")
            return null
        }
    }
}
