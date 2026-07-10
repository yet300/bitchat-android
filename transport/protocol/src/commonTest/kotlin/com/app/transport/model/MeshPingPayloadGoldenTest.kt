package com.app.transport.model

import com.app.transport.protocol.BinaryProtocol
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen wire vectors for the mesh diagnostics ping (0x26) / pong (0x27) payload, derived by hand
 * from the reference iOS encoder (BitFoundation/MeshPingPayload.swift) — never captured from a run.
 *
 * Reference layout, 9 bytes, no endianness ambiguity (both fields are byte-aligned):
 *   [0..7]  nonce      — 8 random bytes; a pong echoes the nonce of the ping it answers
 *   [8]     originTTL  — the TTL the packet was launched with
 *
 * `decode` accepts trailing bytes (forward compatibility); it rejects anything shorter than 9.
 *
 * These bytes MUST NOT change: they are what the reference client puts on the radio.
 */
class MeshPingPayloadGoldenTest {

    private fun ByteArray.hex() = joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun hexToBytes(hex: String) = ByteArray(hex.length / 2) {
        hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private val nonce = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)

    @Test
    fun `golden - payload encodes as nonce then originTTL`() {
        val payload = MeshPingPayload(nonce = nonce, originTTL = 7u)

        // Field layout cross-check (second derivation, independent of the constant below):
        //   nonce      = 00 01 02 03 04 05 06 07
        //   originTTL  = 07
        assertEquals("000102030405060707", payload.encode().hex())
        assertEquals(9, payload.encode().size)
    }

    @Test
    fun `golden - decode round-trips the frozen bytes`() {
        val decoded = MeshPingPayload.decode(hexToBytes("000102030405060707"))

        assertNotNull(decoded)
        assertTrue(nonce.contentEquals(decoded.nonce))
        assertEquals(7u.toUByte(), decoded.originTTL)
    }

    @Test
    fun `decode tolerates trailing bytes so future revisions can extend the payload`() {
        val decoded = MeshPingPayload.decode(hexToBytes("000102030405060707") + byteArrayOf(0xAA.toByte(), 0xBB.toByte()))

        assertNotNull(decoded)
        assertTrue(nonce.contentEquals(decoded.nonce))
        assertEquals(7u.toUByte(), decoded.originTTL)
    }

    @Test
    fun `decode rejects payloads shorter than the fixed 9 bytes`() {
        assertNull(MeshPingPayload.decode(hexToBytes("0001020304050607")))
        assertNull(MeshPingPayload.decode(ByteArray(0)))
    }

    @Test
    fun `construction rejects a nonce that is not 8 bytes`() {
        assertNull(MeshPingPayload.orNull(nonce = ByteArray(7), originTTL = 7u))
        assertNull(MeshPingPayload.orNull(nonce = ByteArray(9), originTTL = 7u))
        assertNotNull(MeshPingPayload.orNull(nonce = ByteArray(8), originTTL = 7u))
    }

    /**
     * hopCount = (originTTL - receivedTTL) + 1: TTL decrements count the relay hops, the +1 is the
     * final delivery link, so a directly connected peer is 1 hop away. Inconsistent TTLs yield null.
     */
    @Test
    fun `hopCount matches the reference formula`() {
        assertEquals(1, MeshPingPayload.hopCount(originTTL = 7u, receivedTTL = 7u))
        assertEquals(3, MeshPingPayload.hopCount(originTTL = 7u, receivedTTL = 5u))
        assertEquals(8, MeshPingPayload.hopCount(originTTL = 7u, receivedTTL = 0u))
        assertNull(MeshPingPayload.hopCount(originTTL = 5u, receivedTTL = 7u))
    }

    /**
     * Full-frame vector: the ping payload inside the already-pinned BitchatPacket envelope.
     * v1 header, directed (recipient present), unsigned, uncompressed (9-byte payload is far
     * below the compression threshold).
     *
     *   01                  version 1
     *   26                  type = ping
     *   07                  ttl = 7 (reference messageTTLDefault)
     *   0000018e0c19c800    timestamp 1709600000000 ms, big-endian
     *   01                  flags = HAS_RECIPIENT
     *   0009                payload length 9, big-endian
     *   1122334455667700    senderID
     *   aabbccddeeff0011    recipientID
     *   000102030405060707  payload
     */
    @Test
    fun `golden - ping frame inside the BitchatPacket envelope`() {
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.PING.value,
            senderID = hexToBytes("1122334455667700"),
            recipientID = hexToBytes("aabbccddeeff0011"),
            timestamp = 1_709_600_000_000uL,
            payload = MeshPingPayload(nonce = nonce, originTTL = 7u).encode(),
            signature = null,
            ttl = 7u,
        )

        val encoded = packet.toBinaryData(padding = false)
        assertNotNull(encoded)
        assertEquals(
            "01" + "26" + "07" +
                "0000018e0c19c800" +
                "01" + "0009" +
                "1122334455667700" +
                "aabbccddeeff0011" +
                "000102030405060707",
            encoded.hex(),
        )
        assertEquals(39, encoded.size)

        val decoded = BinaryProtocol.decode(encoded)
        assertNotNull(decoded)
        assertEquals(MessageType.PING.value, decoded.type)
        assertEquals(7u.toUByte(), decoded.ttl)
        assertNotNull(MeshPingPayload.decode(decoded.payload))
    }

    /**
     * A pong is the same payload under type 0x27, addressed back to the ping's sender, carrying the
     * responder's own fresh originTTL (not the ping's) — the hop count it yields measures the
     * RETURN path.
     */
    @Test
    fun `golden - pong frame echoes the nonce under type 0x27`() {
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.PONG.value,
            senderID = hexToBytes("aabbccddeeff0011"),
            recipientID = hexToBytes("1122334455667700"),
            timestamp = 1_709_600_000_000uL,
            payload = MeshPingPayload(nonce = nonce, originTTL = 7u).encode(),
            signature = null,
            ttl = 7u,
        )

        val encoded = packet.toBinaryData(padding = false)
        assertNotNull(encoded)
        assertEquals(
            "01" + "27" + "07" +
                "0000018e0c19c800" +
                "01" + "0009" +
                "aabbccddeeff0011" +
                "1122334455667700" +
                "000102030405060707",
            encoded.hex(),
        )
    }

    @Test
    fun `wire type values match the reference MessageType enum`() {
        assertEquals(0x26u.toUByte(), MessageType.PING.value)
        assertEquals(0x27u.toUByte(), MessageType.PONG.value)
    }
}
