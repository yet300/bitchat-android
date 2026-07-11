package com.app.transport.model

import com.app.common.encoding.hexEncodedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen wire vectors for courier envelopes (`MessageType.COURIER_ENVELOPE = 0x04`), derived by
 * hand from the reference iOS `BitFoundation/CourierEnvelope.swift` — never captured from a run.
 *
 * TLV: **1-byte** type, **2-byte big-endian** length (contrast vouch's 1-byte length). Optional
 * `copies` (0x04) is omitted when 1; optional `prekeyID` (0x05) is omitted for v1 static seals, so a
 * v1 carry-only envelope stays byte-identical to the pre-spray / pre-prekey format.
 *
 * The recipient-tag KAT pins `HMAC-SHA256(key, "bitchat-courier-tag-v1" || epochDay(u32 BE))[:16]`,
 * which is standard RFC 2104 HMAC and therefore byte-identical to the reference's CryptoKit output.
 */
class CourierEnvelopeGoldenTest {

    // recipientTag = 0x10..0x1f (16 bytes)
    private val tagHex = "101112131415161718191a1b1c1d1e1f"

    // expiry = 2^40 = 1_099_511_627_776 ms
    private val expiry: ULong = 1_099_511_627_776uL
    private val expiryHex = "0000010000000000"

    private val ciphertextHex = "aabbccdd"

    // v1, carry-only (copies == 1 omitted, no prekeyID).
    private val v1Hex = "010010" + tagHex + "020008" + expiryHex + "030004" + ciphertextHex

    // v1, copies == 3 (0x04 TLV present).
    private val v1Copies3Hex = v1Hex + "040001" + "03"

    // v2, copies == 3, prekeyID == 0x01020304 (0x05 TLV present).
    private val v2Hex = v1Copies3Hex + "050004" + "01020304"

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }

    @Test
    fun `golden - expiry constant matches its big-endian encoding`() {
        assertEquals(1uL shl 40, expiry)
        assertEquals(expiryHex, bytes(expiryHex).hexEncodedString())
    }

    @Test
    fun `golden - v1 carry-only encodes to the frozen bytes`() {
        val envelope = CourierEnvelope(
            recipientTag = bytes(tagHex),
            expiry = expiry,
            ciphertext = bytes(ciphertextHex),
        )
        assertEquals(v1Hex, envelope.encode()!!.hexEncodedString())
    }

    @Test
    fun `golden - v1 with copies encodes to the frozen bytes`() {
        val envelope = CourierEnvelope(
            recipientTag = bytes(tagHex),
            expiry = expiry,
            ciphertext = bytes(ciphertextHex),
            copies = 3u,
        )
        assertEquals(v1Copies3Hex, envelope.encode()!!.hexEncodedString())
    }

    @Test
    fun `golden - v2 with prekeyID encodes to the frozen bytes`() {
        val envelope = CourierEnvelope(
            recipientTag = bytes(tagHex),
            expiry = expiry,
            ciphertext = bytes(ciphertextHex),
            copies = 3u,
            prekeyID = 0x01020304u,
        )
        assertEquals(v2Hex, envelope.encode()!!.hexEncodedString())
    }

    @Test
    fun `golden - decode round-trips all three vectors`() {
        for (hex in listOf(v1Hex, v1Copies3Hex, v2Hex)) {
            val decoded = CourierEnvelope.decode(bytes(hex))!!
            assertEquals(hex, decoded.encode()!!.hexEncodedString())
        }
    }

    @Test
    fun `decode - v1 has copies 1 and no prekeyID`() {
        val decoded = CourierEnvelope.decode(bytes(v1Hex))!!
        assertEquals(1u.toUByte(), decoded.copies)
        assertNull(decoded.prekeyID)
        assertEquals(expiry, decoded.expiry)
        assertEquals(tagHex, decoded.recipientTag.hexEncodedString())
        assertEquals(ciphertextHex, decoded.ciphertext.hexEncodedString())
    }

    @Test
    fun `decode - v2 recovers copies and prekeyID`() {
        val decoded = CourierEnvelope.decode(bytes(v2Hex))!!
        assertEquals(3u.toUByte(), decoded.copies)
        assertEquals(0x01020304u, decoded.prekeyID)
    }

    @Test
    fun `decode - skips an unknown TLV for forward compatibility`() {
        // Inject an unknown TLV (type 0x7f, len 2) between ciphertext and end; decode must ignore it.
        val withUnknown = v1Hex + "7f0002" + "cafe"
        val decoded = CourierEnvelope.decode(bytes(withUnknown))!!
        assertEquals(v1Hex, decoded.encode()!!.hexEncodedString())
    }

    @Test
    fun `decode - rejects a truncated length`() {
        // recipientTag TLV claims 16 bytes but the value is short.
        assertNull(CourierEnvelope.decode(bytes("010010" + "1011")))
    }

    @Test
    fun `decode - rejects missing required fields`() {
        // Only expiry present, no recipientTag/ciphertext.
        assertNull(CourierEnvelope.decode(bytes("020008" + expiryHex)))
    }

    @Test
    fun `encode - rejects an oversized ciphertext`() {
        val envelope = CourierEnvelope(
            recipientTag = bytes(tagHex),
            expiry = expiry,
            ciphertext = ByteArray(CourierEnvelope.MAX_CIPHERTEXT_BYTES + 1),
        )
        assertNull(envelope.encode())
    }

    @Test
    fun `copies constructor clamps to 1_maxCopies`() {
        val floor = CourierEnvelope(bytes(tagHex), expiry, bytes(ciphertextHex), copies = 0u)
        assertEquals(1u.toUByte(), floor.copies)
        val ceil = CourierEnvelope(bytes(tagHex), expiry, bytes(ciphertextHex), copies = 200u)
        assertEquals(CourierEnvelope.MAX_COPIES, ceil.copies)
    }

    @Test
    fun `recipientTag - HMAC KAT matches the reference`() {
        val key = ByteArray(32) { 0x2A }
        val tag = CourierEnvelope.recipientTag(key, epochDay = 19700u)
        assertEquals("de7705a279e08006beade4479ac12e5e", tag.hexEncodedString())
        assertEquals(CourierEnvelope.TAG_LENGTH, tag.size)
    }

    @Test
    fun `epochDay - floors unix seconds by 86400`() {
        assertEquals(0u, CourierEnvelope.epochDay(0))
        assertEquals(0u, CourierEnvelope.epochDay(86_399))
        assertEquals(1u, CourierEnvelope.epochDay(86_400))
        assertEquals(19_675u, CourierEnvelope.epochDay(1_700_000_000))
    }

    @Test
    fun `candidateTags - covers adjacent days and matches a same-day seal`() {
        val key = ByteArray(32) { 0x2A }
        val nowMs = 19_700L * 86_400L * 1000L + 1234L
        val tags = CourierEnvelope.candidateTags(key, nowMs)
        assertEquals(3, tags.size)
        val today = CourierEnvelope.recipientTag(key, 19_700u)
        assertTrue(tags.any { it.contentEquals(today) })
        // A tag for an unrelated day is not in the set.
        val farAway = CourierEnvelope.recipientTag(key, 100u)
        assertFalse(tags.any { it.contentEquals(farAway) })
    }
}
