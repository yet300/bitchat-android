package com.app.transport.model

import com.app.common.utils.Log
import com.app.transport.MeshConstants
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * BitchatFilePacket: TLV-encoded file transfer payload for BLE mesh.
 * TLVs:
 *  - 0x01: filename (UTF-8)
 *  - 0x02: file size (4 bytes, UInt32)
 *  - 0x03: mime type (UTF-8)
 *  - 0x04: content (bytes)
 *
 * Filename, file-size, and MIME TLVs use a 2-byte big-endian length. CONTENT uses a 4-byte
 * big-endian length and is emitted as one TLV, allowing the outer v2 packet to exceed 64 KiB.
 *
 * Content is capped at [MeshConstants.FileTransferLimits.MAX_PAYLOAD_BYTES] (1 MiB),
 * matching iOS FileTransferLimits.maxPayloadBytes.
 *
 * Transport-level fragmentation splits the final packet for BLE MTU.
 */
data class BitchatFilePacket(
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val content: ByteArray
) {
    private enum class TLVType(val v: UByte) {
        FILE_NAME(0x01u), FILE_SIZE(0x02u), MIME_TYPE(0x03u), CONTENT(0x04u);
        companion object { fun from(value: UByte) = entries.find { it.v == value } }
    }

    fun encode(): ByteArray? {
        try {
            Log.d("BitchatFilePacket", "🔄 Encoding: name=$fileName, size=$fileSize, mime=$mimeType")
            if (content.isEmpty() || !MeshConstants.FileTransferLimits.isValidPayload(content.size)) {
                Log.e(
                    "BitchatFilePacket",
                    "❌ Content size is outside 1..${MeshConstants.FileTransferLimits.MAX_PAYLOAD_BYTES}: ${content.size}",
                )
                return null
            }
            if (fileSize !in 0..MeshConstants.FileTransferLimits.MAX_PAYLOAD_BYTES.toLong()) {
                Log.e("BitchatFilePacket", "❌ Declared file size is outside native limit: $fileSize")
                return null
            }
            val nameBytes = fileName.encodeToByteArray()
            val mimeBytes = mimeType.encodeToByteArray()
            // Validate the fields that use 2-byte TLV lengths. CONTENT has a 4-byte length.
            if (nameBytes.size > 0xFFFF || mimeBytes.size > 0xFFFF) {
                Log.e("BitchatFilePacket", "❌ TLV field too large: name=${nameBytes.size}, mime=${mimeBytes.size} (max: 65535)")
                return null
            }
            Log.d("BitchatFilePacket", "📏 TLV sizes OK: name=${nameBytes.size}, mime=${mimeBytes.size}, content=${content.size}")

            // kotlinx-io Buffer writes big-endian by default, matching the iOS wire (BIG_ENDIAN).
            val buf = Buffer()

            // FILE_NAME
            buf.writeByte(TLVType.FILE_NAME.v.toByte())
            buf.writeShort(nameBytes.size.toShort())
            buf.write(nameBytes)

            // FILE_SIZE (4 bytes)
            buf.writeByte(TLVType.FILE_SIZE.v.toByte())
            buf.writeShort(4.toShort()) // UInt32 for FILE_SIZE
            buf.writeInt(fileSize.toInt())

            // MIME_TYPE
            buf.writeByte(TLVType.MIME_TYPE.v.toByte())
            buf.writeShort(mimeBytes.size.toShort())
            buf.write(mimeBytes)

            // CONTENT (single TLV with 4-byte length)
            buf.writeByte(TLVType.CONTENT.v.toByte())
            buf.writeInt(content.size)
            buf.write(content)

            val result = buf.readByteArray()
            Log.d("BitchatFilePacket", "✅ Encoded successfully: ${result.size} bytes total")
            return result
        } catch (e: Exception) {
            Log.e("BitchatFilePacket", "❌ Encoding failed: ${e.message}", e)
            return null
        }
    }

    companion object {
        fun decode(data: ByteArray): BitchatFilePacket? {
            Log.d("BitchatFilePacket", "🔄 Decoding ${data.size} bytes")
            try {
                var off = 0
                var name: String? = null
                var size: Long? = null
                var mime: String? = null
                var contentBytes: ByteArray? = null
                while (off + 3 <= data.size) { // minimum TLV header size (type + 2 bytes length)
                    val t = TLVType.from(data[off].toUByte()) ?: return null
                    off += 1
                    // CONTENT uses 4-byte length; others use 2-byte length
                    val len: Int
                    if (t == TLVType.CONTENT) {
                        if (off + 4 > data.size) return null
                        len = ((data[off].toInt() and 0xFF) shl 24) or ((data[off + 1].toInt() and 0xFF) shl 16) or ((data[off + 2].toInt() and 0xFF) shl 8) or (data[off + 3].toInt() and 0xFF)
                        off += 4
                    } else {
                        if (off + 2 > data.size) return null
                        len = ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)
                        off += 2
                    }
                    if (len < 0 || len > data.size - off) return null
                    if (t == TLVType.CONTENT) {
                        val accumulated = contentBytes?.size ?: 0
                        if (len > MeshConstants.FileTransferLimits.MAX_PAYLOAD_BYTES - accumulated) {
                            return null
                        }
                    }
                    val value = data.copyOfRange(off, off + len)
                    off += len
                    when (t) {
                        TLVType.FILE_NAME -> name = value.decodeToString()
                        TLVType.FILE_SIZE -> {
                            if (len != 4) return null
                            // Signed big-endian int -> Long (sign-extended), identical to ByteBuffer.int.toLong().
                            val iv = ((value[0].toInt() and 0xFF) shl 24) or
                                ((value[1].toInt() and 0xFF) shl 16) or
                                ((value[2].toInt() and 0xFF) shl 8) or
                                (value[3].toInt() and 0xFF)
                            size = iv.toLong()
                        }
                        TLVType.MIME_TYPE -> mime = value.decodeToString()
                        TLVType.CONTENT -> {
                            // Expect a single CONTENT TLV
                            contentBytes = if (contentBytes == null) value else {
                                // If multiple CONTENT TLVs appear, concatenate for tolerance
                                (contentBytes + value)
                            }
                        }
                    }
                }
                val n = name ?: return null
                val c = contentBytes?.takeIf { it.isNotEmpty() } ?: return null
                if (!MeshConstants.FileTransferLimits.isValidPayload(c.size)) {
                    Log.e(
                        "BitchatFilePacket",
                        "❌ Decoded content exceeds max payload: ${c.size} > ${MeshConstants.FileTransferLimits.MAX_PAYLOAD_BYTES}",
                    )
                    return null
                }
                val s = size ?: c.size.toLong()
                if (s !in 0..MeshConstants.FileTransferLimits.MAX_PAYLOAD_BYTES.toLong()) return null
                val m = mime ?: "application/octet-stream"
                val result = BitchatFilePacket(n, s, m, c)
                Log.d("BitchatFilePacket", "✅ Decoded: name=$n, size=$s, mime=$m, content=${c.size} bytes")
                return result
            } catch (e: Exception) {
                Log.e("BitchatFilePacket", "❌ Decoding failed: ${e.message}", e)
                return null
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as BitchatFilePacket

        if (fileSize != other.fileSize) return false
        if (fileName != other.fileName) return false
        if (mimeType != other.mimeType) return false
        if (!content.contentEquals(other.content)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = fileSize.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + content.contentHashCode()
        return result
    }
}
