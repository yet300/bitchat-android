package com.app.transport.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire vectors for the capabilities TLV (0x05) inside the announce payload, hand-derived from the
 * reference iOS `Protocols/Packets.swift` (AnnouncementPacket encode/decode) and
 * `BitFoundation/PeerCapabilities.swift`.
 *
 * The reference TLV set is 0x01 nickname, 0x02 noise key, 0x03 signing key, 0x04 directNeighbors,
 * 0x05 capabilities, 0x06 bridgeGeohash. Both decoders walk the TLVs in a type-switched loop, so
 * field order is not load-bearing; unknown types are skipped.
 */
class AnnounceCapabilitiesGoldenTest {

    private fun ByteArray.hex() = joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun tlv(type: Int, value: ByteArray): ByteArray =
        byteArrayOf(type.toByte(), value.size.toByte()) + value

    private val noiseKey = ByteArray(32) { 0xBB.toByte() }
    private val signingKey = ByteArray(32) { 0xAA.toByte() }

    // --- (a) regression: announces without 0x05 decode exactly as before ---

    @Test
    fun `announce without the capabilities TLV decodes with null capabilities`() {
        val payload = tlv(0x01, "alice".encodeToByteArray()) +
            tlv(0x02, noiseKey) +
            tlv(0x03, signingKey)

        val decoded = IdentityAnnouncement.decode(payload)

        assertNotNull(decoded)
        assertEquals("alice", decoded.nickname)
        assertNull(decoded.capabilities, "absent TLV must stay distinguishable from an empty set")
    }

    @Test
    fun `announce with the iOS directNeighbors TLV and no capabilities still decodes`() {
        val payload = tlv(0x01, "bob".encodeToByteArray()) +
            tlv(0x02, noiseKey) +
            tlv(0x03, signingKey) +
            tlv(0x04, ByteArray(16))

        val decoded = IdentityAnnouncement.decode(payload)

        assertNotNull(decoded)
        assertNull(decoded.capabilities)
    }

    @Test
    fun `our encoder emits no capabilities TLV when the set is null`() {
        val encoded = IdentityAnnouncement("carol", noiseKey, signingKey, capabilities = null).encode()

        assertNotNull(encoded)
        val expected = tlv(0x01, "carol".encodeToByteArray()) + tlv(0x02, noiseKey) + tlv(0x03, signingKey)
        assertEquals(expected.hex(), encoded.hex())
    }

    // --- (b) reading: a peer's 0x05 lands in `capabilities` ---

    /**
     * Reference bytes for an announce from a peer advertising the reference's own
     * `localSupported` = [.vouch, .prekeys, .groups] = 0x29:
     *   01 05 "alice"                 nickname
     *   02 20 bb*32                   noise key
     *   03 20 aa*32                   signing key
     *   04 10 00*16                   two direct neighbors
     *   05 01 29                      capabilities
     */
    @Test
    fun `golden - announce carrying capabilities 0x29 decodes to vouch plus prekeys plus groups`() {
        val payload = tlv(0x01, "alice".encodeToByteArray()) +
            tlv(0x02, noiseKey) +
            tlv(0x03, signingKey) +
            tlv(0x04, ByteArray(16)) +
            byteArrayOf(0x05, 0x01, 0x29)

        val decoded = IdentityAnnouncement.decode(payload)

        assertNotNull(decoded)
        val caps = decoded.capabilities
        assertNotNull(caps)
        assertEquals(PeerCapabilities.VOUCH + PeerCapabilities.PREKEYS + PeerCapabilities.GROUPS, caps)
        assertTrue(caps.contains(PeerCapabilities.VOUCH))
        assertTrue(caps.contains(PeerCapabilities.PREKEYS))
        assertTrue(caps.contains(PeerCapabilities.GROUPS))
    }

    @Test
    fun `an explicitly empty capabilities TLV decodes to the empty set rather than null`() {
        val payload = tlv(0x01, "dave".encodeToByteArray()) +
            tlv(0x02, noiseKey) +
            tlv(0x03, signingKey) +
            byteArrayOf(0x05, 0x01, 0x00)

        val decoded = IdentityAnnouncement.decode(payload)

        assertNotNull(decoded)
        assertEquals(PeerCapabilities.NONE, decoded.capabilities)
    }

    /** A newer peer's unassigned high bits must not break the decode. */
    @Test
    fun `announce with unknown high capability bits decodes and preserves them`() {
        val payload = tlv(0x01, "eve".encodeToByteArray()) +
            tlv(0x02, noiseKey) +
            tlv(0x03, signingKey) +
            byteArrayOf(0x05, 0x03, 0x01, 0x00, 0x02)  // bit 0 (prekeys) + bit 17

        val decoded = IdentityAnnouncement.decode(payload)

        assertNotNull(decoded)
        val caps = decoded.capabilities
        assertNotNull(caps)
        assertTrue(caps.contains(PeerCapabilities.PREKEYS))
        assertEquals(0x020001uL, caps.rawValue)
    }

    /** Field order is not load-bearing: the reference decoder is a type-switched loop too. */
    @Test
    fun `capabilities are read regardless of TLV order`() {
        val payload = byteArrayOf(0x05, 0x01, 0x51) +
            tlv(0x02, noiseKey) +
            tlv(0x01, "frank".encodeToByteArray()) +
            tlv(0x04, ByteArray(8)) +
            tlv(0x03, signingKey)

        val decoded = IdentityAnnouncement.decode(payload)

        assertNotNull(decoded)
        assertEquals("frank", decoded.nickname)
        assertEquals(PeerCapabilities(0x51uL), decoded.capabilities)
    }

    // --- (c) writing: our encoder produces bytes the reference decoder accepts ---

    /**
     * Our announce with capabilities, byte for byte:
     *   01 05 67 72 61 63 65    nickname "grace" (g=0x67 r=0x72 a=0x61 c=0x63 e=0x65)
     *   02 20 bb*32             noise key
     *   03 20 aa*32             signing key
     *   05 01 51                capabilities [.prekeys, .board, .meshDiagnostics]
     *
     * The reference `AnnouncementPacket.decode` walks these TLVs by type: it takes 0x01/0x02/0x03
     * for the identity fields and `PeerCapabilities(encoded:)` on the 0x05 value, so it reconstructs
     * exactly the set we encoded.
     */
    @Test
    fun `golden - our encoder emits 05 01 51 for prekeys plus board plus meshDiagnostics`() {
        val caps = PeerCapabilities.PREKEYS + PeerCapabilities.BOARD + PeerCapabilities.MESH_DIAGNOSTICS

        val encoded = IdentityAnnouncement("grace", noiseKey, signingKey, caps).encode()

        assertNotNull(encoded)
        assertEquals(
            "0105" + "6772616365" +
                "0220" + "bb".repeat(32) +
                "0320" + "aa".repeat(32) +
                "050151",
            encoded.hex(),
        )
    }

    /** Round trip through our own decoder, which mirrors the reference's TLV walk. */
    @Test
    fun `encoder output round-trips through the decoder for every single bit`() {
        listOf(
            PeerCapabilities.NONE,
            PeerCapabilities.PREKEYS,
            PeerCapabilities.WIFI_BULK,
            PeerCapabilities.GATEWAY,
            PeerCapabilities.GROUPS,
            PeerCapabilities.BOARD,
            PeerCapabilities.VOUCH,
            PeerCapabilities.MESH_DIAGNOSTICS,
            PeerCapabilities.BRIDGE,
        ).forEach { caps ->
            val encoded = IdentityAnnouncement("h", noiseKey, signingKey, caps).encode()
            assertNotNull(encoded)
            assertEquals(caps, IdentityAnnouncement.decode(encoded)?.capabilities)
        }
    }

    /**
     * The bytes we actually put on the radio now carry a 0x05 capabilities TLV of `[.vouch]` = 0x20,
     * appended after the identity TLVs. A reference peer reads the bit and knows we accept vouch
     * batches; a pre-capabilities peer skips the unknown TLV. `05 01 20` is the whole addition.
     */
    @Test
    fun `our production announce advertises the vouch capability`() {
        val advertised = PeerCapabilities.LOCAL_SUPPORTED.takeIf { !it.isEmpty() }
        val encoded = IdentityAnnouncement("ivan", noiseKey, signingKey, advertised).encode()

        assertNotNull(encoded)
        assertEquals(PeerCapabilities.VOUCH, advertised)
        val expected = tlv(0x01, "ivan".encodeToByteArray()) +
            tlv(0x02, noiseKey) +
            tlv(0x03, signingKey) +
            byteArrayOf(0x05, 0x01, 0x20)
        assertEquals(expected.hex(), encoded.hex())
        assertEquals(PeerCapabilities.VOUCH, IdentityAnnouncement.decode(encoded)?.capabilities)
    }
}
