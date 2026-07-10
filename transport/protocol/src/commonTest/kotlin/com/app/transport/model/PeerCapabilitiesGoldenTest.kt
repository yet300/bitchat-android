package com.app.transport.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Frozen wire vectors for the announce capabilities TLV (0x05), derived by hand from the reference
 * iOS `BitFoundation/PeerCapabilities.swift` — never captured from a run.
 *
 * Encoding: little-endian bitfield with trailing zero bytes dropped, always at least one byte so an
 * empty set stays distinguishable from an absent TLV. Decoding keeps the low 64 bits and ignores a
 * longer field, so unknown high bits from a newer peer degrade per-feature instead of failing.
 *
 * Reference bit assignment (`1 << n`):
 *   0 prekeys   1 wifiBulk   2 gateway   3 groups
 *   4 board     5 vouch      6 meshDiagnostics   7 bridge
 */
class PeerCapabilitiesGoldenTest {

    private fun ByteArray.hex() = joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun `golden - bit values match the reference OptionSet`() {
        assertEquals(1uL, PeerCapabilities.PREKEYS.rawValue)
        assertEquals(2uL, PeerCapabilities.WIFI_BULK.rawValue)
        assertEquals(4uL, PeerCapabilities.GATEWAY.rawValue)
        assertEquals(8uL, PeerCapabilities.GROUPS.rawValue)
        assertEquals(16uL, PeerCapabilities.BOARD.rawValue)
        assertEquals(32uL, PeerCapabilities.VOUCH.rawValue)
        assertEquals(64uL, PeerCapabilities.MESH_DIAGNOSTICS.rawValue)
        assertEquals(128uL, PeerCapabilities.BRIDGE.rawValue)
    }

    /**
     * The reference's own `localSupported` = [.vouch, .prekeys, .groups]
     *   = (1 << 5) | (1 << 0) | (1 << 3) = 32 + 1 + 8 = 41 = 0x29
     * One byte: 0x29 >> 8 == 0, so the encoder's do/while stops after the first byte.
     */
    @Test
    fun `golden - reference localSupported encodes to a single byte 0x29`() {
        val caps = PeerCapabilities.VOUCH + PeerCapabilities.PREKEYS + PeerCapabilities.GROUPS

        assertEquals(41uL, caps.rawValue)
        assertEquals("29", caps.encoded().hex())
    }

    /**
     * The reference's own PeerCapabilitiesTests fixture: [.prekeys, .board, .meshDiagnostics]
     *   = (1 << 0) | (1 << 4) | (1 << 6) = 1 + 16 + 64 = 81 = 0x51
     */
    @Test
    fun `golden - prekeys plus board plus meshDiagnostics encodes to 0x51`() {
        val caps = PeerCapabilities.PREKEYS + PeerCapabilities.BOARD + PeerCapabilities.MESH_DIAGNOSTICS

        assertEquals(81uL, caps.rawValue)
        assertEquals("51", caps.encoded().hex())
        assertEquals(caps, PeerCapabilities.decode(byteArrayOf(0x51)))
    }

    /** Empty set: the do/while body runs once, so one 0x00 byte — never a zero-length value. */
    @Test
    fun `golden - the empty set encodes to a single zero byte`() {
        assertEquals("00", PeerCapabilities.NONE.encoded().hex())
        assertEquals(1, PeerCapabilities.NONE.encoded().size)
        assertTrue(PeerCapabilities.NONE.isEmpty())
    }

    /** The highest currently assigned bit still fits one byte. */
    @Test
    fun `golden - bridge alone encodes to 0x80`() {
        assertEquals("80", PeerCapabilities.BRIDGE.encoded().hex())
    }

    /**
     * Multi-byte, little-endian, trailing zeros dropped. rawValue 0x0102:
     *   byte 0 = 0x02 (low), value >>= 8 -> 0x01 (non-zero, loop again)
     *   byte 1 = 0x01,       value >>= 8 -> 0    (stop)
     */
    @Test
    fun `golden - multi-byte fields are little-endian with trailing zeros dropped`() {
        assertEquals("0201", PeerCapabilities(0x0102uL).encoded().hex())
        assertEquals("0001", PeerCapabilities(0x0100uL).encoded().hex())
        assertEquals("ffffffffffffffff", PeerCapabilities(ULong.MAX_VALUE).encoded().hex())
    }

    @Test
    fun `decode reads little-endian and round-trips the encoder`() {
        assertEquals(PeerCapabilities(0x0102uL), PeerCapabilities.decode(byteArrayOf(0x02, 0x01)))
        listOf(0uL, 1uL, 41uL, 81uL, 128uL, 0x0102uL, ULong.MAX_VALUE).forEach { raw ->
            val caps = PeerCapabilities(raw)
            assertEquals(caps, PeerCapabilities.decode(caps.encoded()))
        }
    }

    /** Forward compatibility: an empty value means "no bits", a longer field keeps the low 64. */
    @Test
    fun `decode tolerates an empty value and ignores bytes beyond the low 64 bits`() {
        assertEquals(PeerCapabilities.NONE, PeerCapabilities.decode(ByteArray(0)))

        val nineBytes = ByteArray(9) { if (it == 0) 0x51 else 0xFF.toByte() }
        assertEquals(PeerCapabilities(0xFFFFFFFFFFFFFF51uL), PeerCapabilities.decode(nineBytes))
    }

    /** Unknown high bits from a newer peer are preserved, not masked away. */
    @Test
    fun `unknown high bits survive a decode-encode round trip`() {
        val future = PeerCapabilities.decode(byteArrayOf(0x00, 0x00, 0x01))

        assertEquals(0x010000uL, future.rawValue)
        assertFalse(future.contains(PeerCapabilities.PREKEYS))
        assertEquals("000001", future.encoded().hex())
    }

    @Test
    fun `contains tests a single bit rather than the whole field`() {
        val caps = PeerCapabilities.PREKEYS + PeerCapabilities.BRIDGE

        assertTrue(caps.contains(PeerCapabilities.PREKEYS))
        assertTrue(caps.contains(PeerCapabilities.BRIDGE))
        assertFalse(caps.contains(PeerCapabilities.VOUCH))
        assertFalse(caps.isEmpty())
    }

    /**
     * We advertise nothing yet: every reference bit names a feature this client does not implement
     * (prekeys/groups/board/vouch/gateway/bridge/wifiBulk). meshDiagnostics is deliberately not
     * claimed either — the reference implements ping/pong and still leaves it out of its own
     * `localSupported`, so its exact contract is not settled. An empty set emits no TLV at all,
     * which is what the reference's decoder collapses an empty value to anyway.
     */
    @Test
    fun `we advertise no capabilities yet`() {
        assertTrue(PeerCapabilities.LOCAL_SUPPORTED.isEmpty())
    }
}
