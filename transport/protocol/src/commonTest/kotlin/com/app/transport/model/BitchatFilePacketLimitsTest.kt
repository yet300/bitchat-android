package com.app.transport.model

import com.app.transport.MeshConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P0.4: 1 MiB content limit and framed-packet headroom for fragment reassembly. */
class BitchatFilePacketLimitsTest {

    @Test
    fun encodeRejectsContentOver1MiB() {
        val over = MeshConstants.FileTransferLimits.MAX_PAYLOAD_BYTES + 1
        val packet = BitchatFilePacket(
            fileName = "big.bin",
            fileSize = over.toLong(),
            mimeType = "application/octet-stream",
            content = ByteArray(over) { 0xAB.toByte() },
        )
        assertNull(packet.encode())
    }

    @Test
    fun encodeRejectsDeclaredSizeOutsideNativeLimit() {
        val packet = BitchatFilePacket(
            fileName = "bad.bin",
            fileSize = MeshConstants.FileTransferLimits.MAX_PAYLOAD_BYTES.toLong() + 1,
            mimeType = "application/octet-stream",
            content = byteArrayOf(1),
        )

        assertNull(packet.encode())
    }

    @Test
    fun encodeRejectsEmptyContentLikeNativeDecoder() {
        val packet = BitchatFilePacket(
            fileName = "empty.bin",
            fileSize = 0,
            mimeType = "application/octet-stream",
            content = byteArrayOf(),
        )

        assertNull(packet.encode())
    }

    @Test
    fun encodeAcceptsExact1MiBContent() {
        val size = MeshConstants.FileTransferLimits.MAX_PAYLOAD_BYTES
        val packet = BitchatFilePacket(
            fileName = "a.bin",
            fileSize = size.toLong(),
            mimeType = "application/octet-stream",
            content = ByteArray(size) { (it % 251).toByte() },
        )
        val encoded = packet.encode()
        assertNotNull(encoded)
        val decoded = BitchatFilePacket.decode(encoded)
        assertNotNull(decoded)
        assertEquals(size, decoded.content.size)
    }

    @Test
    fun decodeRejectsContentOver1MiB() {
        val size = MeshConstants.FileTransferLimits.MAX_PAYLOAD_BYTES
        val ok = BitchatFilePacket(
            fileName = "a.bin",
            fileSize = size.toLong(),
            mimeType = "application/octet-stream",
            content = ByteArray(size),
        ).encode()!!
        val secondContentTlv = byteArrayOf(0x04, 0x00, 0x00, 0x00, 0x01, 0x7F)

        assertNull(BitchatFilePacket.decode(ok + secondContentTlv))
    }

    @Test
    fun decodeRejectsDeclaredSizeOutsideNativeLimit() {
        val malformed = byteArrayOf(
            0x01, 0x00, 0x01, 'a'.code.toByte(),
            0x02, 0x00, 0x04, 0x00, 0x10, 0x00, 0x01,
            0x04, 0x00, 0x00, 0x00, 0x01, 0x7F,
        )

        assertNull(BitchatFilePacket.decode(malformed))
    }

    @Test
    fun maxFramedFileBytesExceedsBare1MiB() {
        // Fragment cumulative gate must fit content + TLV + outer packet envelope.
        assertTrue(
            MeshConstants.Fragmentation.MAX_FRAGMENT_TOTAL_BYTES >
                MeshConstants.FileTransferLimits.MAX_PAYLOAD_BYTES,
        )
        assertTrue(
            MeshConstants.Fragmentation.MAX_FRAGMENT_TOTAL_BYTES >=
                MeshConstants.FileTransferLimits.maxFramedFileBytes,
        )
    }
}
