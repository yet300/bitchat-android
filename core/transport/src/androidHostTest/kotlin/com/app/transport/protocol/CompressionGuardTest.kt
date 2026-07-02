package com.app.transport.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * H2 hardening guards: decompression is bounded by the claimed original size, which is
 * attacker-controlled and processed before any signature check. Honest round-trips (peer
 * declares the exact plain size) must keep working byte-for-byte; frames that lie about
 * the plain size in either direction must be rejected instead of allocating unbounded
 * output. Wire compatibility of honest compressed frames is separately pinned by
 * BinaryProtocolGoldenTest / CompressionInteropTest.
 */
class CompressionGuardTest {

    private val compressible = ByteArray(4096) { 'A'.code.toByte() }

    @Test
    fun `honest round-trip with exact declared size still inflates byte-for-byte`() {
        val compressed = CompressionUtil.compress(compressible)
        assertNotNull(compressed)
        val decompressed = CompressionUtil.decompress(compressed!!, compressible.size)
        assertNotNull(decompressed)
        assertArrayEquals(compressible, decompressed)
    }

    @Test
    fun `stream that inflates past the claimed size is rejected`() {
        // The stream really inflates to 4096 bytes; a peer claiming 1000 is smuggling a bomb.
        val compressed = CompressionUtil.compress(compressible)!!
        assertNull(CompressionUtil.decompress(compressed, 1000))
    }

    @Test
    fun `stream that ends before the claimed size is rejected`() {
        val compressed = CompressionUtil.compress(compressible)!!
        assertNull(CompressionUtil.decompress(compressed, compressible.size + 1))
    }

    @Test
    fun `claimed size above the wire payload cap is rejected before allocation`() {
        val compressed = CompressionUtil.compress(compressible)!!
        assertNull(CompressionUtil.decompress(compressed, 10_485_761))
    }

    @Test
    fun `non-positive claimed sizes are rejected`() {
        val compressed = CompressionUtil.compress(compressible)!!
        assertNull(CompressionUtil.decompress(compressed, 0))
        assertNull(CompressionUtil.decompress(compressed, -1))
    }

    @Test
    fun `compressed packet with lying original-size field is dropped by decode`() {
        // Encode an honest compressed packet, then corrupt the plain-size field (the two
        // bytes right after the v1 header block) and check the decoder drops the frame.
        val packet = BitchatPacket(
            version = 1u, type = MessageType.MESSAGE.value,
            senderID = ByteArray(8) { (it + 1).toByte() }, recipientID = null,
            timestamp = 1_700_000_000_000uL, payload = compressible, ttl = 7u,
        )
        val encoded = BinaryProtocol.encode(packet, padding = false)
        assertNotNull(encoded)
        assertNotNull(BitchatPacket.fromBinaryData(encoded!!))

        // v1 header: 1 version + 1 type + 1 ttl + 8 timestamp + 1 flags + 2 payloadLength
        // + 8 senderID = 22 bytes; the compressed payload starts with the 2-byte plain size.
        val corrupted = encoded.copyOf()
        corrupted[22] = 0x00
        corrupted[23] = 0x10 // claim 16 bytes instead of 4096
        assertNull(BitchatPacket.fromBinaryData(corrupted))
    }
}
