package com.app.transport.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Frozen wire-format vectors: the full encode() output for five representative packets,
 * captured from the implementation as of 2026-06-10 (commit pinning iOS compatibility).
 *
 * These bytes MUST NOT change. If any of these assertions fails, the codec drifted and
 * the change breaks compatibility with iOS peers — fix the code, do not update the
 * fixtures (unless the wire protocol itself is intentionally versioned forward and the
 * fixtures are re-captured against a verified-compatible implementation).
 */
class BinaryProtocolGoldenTest {

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private val sender = ByteArray(8) { (it + 1).toByte() }             // 0102030405060708
    private val recipient = ByteArray(8) { (0x11 * (it + 1)).toByte() } // 1122334455667788
    private val signature = ByteArray(64) { it.toByte() }
    private val timestamp = 1_700_000_000_000uL
    private val compressible = ByteArray(400) { 'A'.code.toByte() }

    @Test
    fun `golden - v1 plain broadcast`() {
        val packet = BitchatPacket(
            version = 1u, type = MessageType.MESSAGE.value, senderID = sender,
            recipientID = null, timestamp = timestamp,
            payload = "hello bitchat".toByteArray(Charsets.UTF_8), ttl = 7u,
        )
        val encoded = BinaryProtocol.encode(packet)
        assertNotNull(encoded)
        assertEquals(
            "0102070000018bcfe5680000000d010203040506070868656c6c6f2062697463686174" +
                "dd".repeat(221),
            encoded!!.hex(),
        )
    }

    @Test
    fun `golden - v1 with recipient and signature`() {
        val packet = BitchatPacket(
            version = 1u, type = MessageType.MESSAGE.value, senderID = sender,
            recipientID = recipient, timestamp = timestamp,
            payload = "private hello".toByteArray(Charsets.UTF_8),
            signature = signature, ttl = 3u,
        )
        val encoded = BinaryProtocol.encode(packet)
        assertNotNull(encoded)
        assertEquals(
            "0102030000018bcfe5680003000d01020304050607081122334455667788" +
                "707269766174652068656c6c6f" +
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f" +
                "95".repeat(149),
            encoded!!.hex(),
        )
    }

    @Test
    fun `golden - v2 with route`() {
        val packet = BitchatPacket(
            version = 2u, type = MessageType.MESSAGE.value, senderID = sender,
            recipientID = recipient, timestamp = timestamp,
            payload = "routed hello".toByteArray(Charsets.UTF_8), ttl = 5u,
            route = listOf(ByteArray(8) { 0x0A }, ByteArray(8) { 0x0B }),
        )
        val encoded = BinaryProtocol.encode(packet)
        assertNotNull(encoded)
        assertEquals(
            "0202050000018bcfe56800090000000c01020304050607081122334455667788" +
                "020a0a0a0a0a0a0a0a0b0b0b0b0b0b0b0b726f757465642068656c6c6f" +
                "c3".repeat(195),
            encoded!!.hex(),
        )
    }

    @Test
    fun `golden - v1 compressed`() {
        val packet = BitchatPacket(
            version = 1u, type = MessageType.MESSAGE.value, senderID = sender,
            recipientID = null, timestamp = timestamp,
            payload = compressible, ttl = 7u,
        )
        val encoded = BinaryProtocol.encode(packet)
        assertNotNull(encoded)
        // Compressed bytes re-captured for the Kompress (pure-Kotlin KMP) DEFLATE codec; the
        // stream is one byte shorter than the old JDK encoder but inflates identically (see
        // CompressionInteropTest). Plain/uncompressed golden vectors are unaffected.
        assertEquals(
            "0102070000018bcfe5680004000901020304050607080190731c05830a0000" +
                "e1".repeat(225),
            encoded!!.hex(),
        )
    }

    @Test
    fun `golden - v2 compressed`() {
        val packet = BitchatPacket(
            version = 2u, type = MessageType.MESSAGE.value, senderID = sender,
            recipientID = recipient, timestamp = timestamp,
            payload = compressible, ttl = 7u,
        )
        val encoded = BinaryProtocol.encode(packet)
        assertNotNull(encoded)
        // Re-captured for the Kompress DEFLATE codec (see the v1 note / CompressionInteropTest).
        assertEquals(
            "0202070000018bcfe56800050000000b0102030405060708112233445566778800000190" +
                "731c05830a0000" +
                "d5".repeat(213),
            encoded!!.hex(),
        )
    }
}
