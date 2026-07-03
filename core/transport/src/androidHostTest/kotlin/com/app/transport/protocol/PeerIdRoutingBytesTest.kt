package com.app.transport.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Pins the exact behavior of the consolidated peerIdToRoutingBytes helper so the
 * consolidation of the four historical private copies stays byte-identical.
 * The lenient zero-fill policy for malformed input is intentional legacy behavior
 * (iOS Data(hexString:) is stricter); changing it is an owner decision (review L2).
 */
class PeerIdRoutingBytesTest {

    @Test
    fun `valid 16-hex peer ID converts to exact 8 bytes`() {
        assertArrayEquals(
            byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte()),
            peerIdToRoutingBytes("1122334455667788")
        )
    }

    @Test
    fun `uppercase hex is accepted`() {
        assertArrayEquals(
            byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0, 0, 0, 0),
            peerIdToRoutingBytes("AABBCCDD")
        )
    }

    @Test
    fun `short input is zero-padded at the tail`() {
        assertArrayEquals(
            byteArrayOf(0x12, 0x34, 0, 0, 0, 0, 0, 0),
            peerIdToRoutingBytes("1234")
        )
    }

    @Test
    fun `input longer than 16 hex chars is truncated to 8 bytes`() {
        assertArrayEquals(
            byteArrayOf(0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11),
            peerIdToRoutingBytes("11111111111111112222")
        )
    }

    @Test
    fun `odd-length input parses whole pairs and drops the dangling nibble`() {
        assertArrayEquals(
            byteArrayOf(0x12, 0x34, 0, 0, 0, 0, 0, 0),
            peerIdToRoutingBytes("12345")
        )
    }

    @Test
    fun `non-hex pairs are left as zero bytes`() {
        assertArrayEquals(
            byteArrayOf(0x12, 0, 0x34, 0, 0, 0, 0, 0),
            peerIdToRoutingBytes("12zz34")
        )
    }

    @Test
    fun `empty input yields the all-zero routing ID`() {
        assertArrayEquals(ByteArray(8), peerIdToRoutingBytes(""))
    }
}
