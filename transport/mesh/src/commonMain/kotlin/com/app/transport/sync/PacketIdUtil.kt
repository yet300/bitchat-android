package com.app.transport.sync

import com.app.common.encoding.hexEncodedString
import com.app.transport.crypto.Sha256
import com.app.transport.protocol.BitchatPacket
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * Deterministic packet ID helper for sync purposes.
 * Uses SHA-256 over a canonical subset of packet fields:
 * [type | senderID | timestamp | payload] to generate a stable ID.
 * Returns a 16-byte (128-bit) truncated hash for compactness.
 */
internal object PacketIdUtil {
    fun computeIdBytes(packet: BitchatPacket): ByteArray {
        // Canonical byte layout: type(1) | senderID | timestamp(8, big-endian) | payload.
        // writeLong is big-endian, matching the previous manual byte loop.
        val buffer = Buffer()
        buffer.writeByte(packet.type.toByte())
        buffer.write(packet.senderID)
        buffer.writeLong(packet.timestamp.toLong())
        buffer.write(packet.payload)
        val digest = Sha256.digest(buffer.readByteArray())
        return digest.copyOf(16) // 128-bit ID
    }

    fun computeIdHex(packet: BitchatPacket): String {
        return computeIdBytes(packet).hexEncodedString()
    }
}

