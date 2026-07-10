package com.app.transport.model

import com.app.common.encoding.hexEncodedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen wire vectors for vouch attestations (NoisePayload 0x12), derived by hand from the
 * reference iOS `Protocols/VouchAttestation.swift` — never captured from a run.
 *
 * Single attestation: TLV with a **1-byte** type and a **1-byte** length (contrast the prekey
 * bundle, which uses a 2-byte big-endian length).
 *
 * Signed transcript has **no length prefix** on the context string:
 *   `"bitchat-vouch-v1" | voucheeFingerprint(32) | voucheeSigningKey(32) | timestampMs(8 BE)`
 */
class VouchAttestationGoldenTest {

    // 0x01..0x20
    private val fingerprintHex = "0102030405060708090a0b0c0d0e0f10" + "1112131415161718191a1b1c1d1e1f20"

    // 0x21..0x40
    private val signingKeyHex = "2122232425262728292a2b2c2d2e2f30" + "3132333435363738393a3b3c3d3e3f40"

    // 0x41..0x80
    private val signatureHex =
        "4142434445464748494a4b4c4d4e4f50" +
            "5152535455565758595a5b5c5d5e5f60" +
            "6162636465666768696a6b6c6d6e6f70" +
            "7172737475767778797a7b7c7d7e7f80"

    /** 0x0000019200000000 = 0x192 * 2^32 = 402 * 4294967296. */
    private val timestampMs: ULong = 1_726_576_852_992uL
    private val timestampHex = "0000019200000000"

    /** "bitchat-vouch-v1", 16 ASCII bytes, no length prefix. */
    private val contextHex = "626974636861742d766f7563682d7631"

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }

    private fun attestation() = VouchAttestation(
        voucheeFingerprint = bytes(fingerprintHex),
        voucheeSigningKey = bytes(signingKeyHex),
        timestampMs = timestampMs,
        signature = bytes(signatureHex),
    )

    @Test
    fun `golden - timestamp constant matches its big-endian encoding`() {
        assertEquals(402uL * 4_294_967_296uL, timestampMs)
        assertEquals(timestampHex, bytes(timestampHex).hexEncodedString())
    }

    @Test
    fun `golden - constants match the reference`() {
        assertEquals("bitchat-vouch-v1", VouchAttestation.SIGNING_CONTEXT)
        assertEquals(16, VouchAttestation.SIGNING_CONTEXT.encodeToByteArray().size)
        assertEquals(30L * 24 * 60 * 60 * 1000, VouchAttestation.MAX_AGE_MS)
        assertEquals(60L * 60 * 1000, VouchAttestation.MAX_CLOCK_SKEW_MS)
        assertEquals(16, VouchAttestation.MAX_BATCH_COUNT)
        assertEquals(32, VouchAttestation.FINGERPRINT_SIZE)
        assertEquals(32, VouchAttestation.SIGNING_KEY_SIZE)
        assertEquals(64, VouchAttestation.SIGNATURE_SIZE)
    }

    /** 16 + 32 + 32 + 8 = 88 bytes, context first, no length prefix. */
    @Test
    fun `golden - signable transcript bytes`() {
        val expected = contextHex + fingerprintHex + signingKeyHex + timestampHex
        assertEquals(88, expected.length / 2)
        assertEquals(expected, attestation().signableBytes.hexEncodedString())
    }

    /** (2+32) + (2+32) + (2+8) + (2+64) = 144 bytes, TLV order 0x01,0x02,0x03,0x04. */
    @Test
    fun `golden - single attestation encoding`() {
        val expected =
            "0120" + fingerprintHex +
                "0220" + signingKeyHex +
                "0308" + timestampHex +
                "0440" + signatureHex
        assertEquals(144, expected.length / 2)
        assertEquals(expected, attestation().encode()!!.hexEncodedString())
    }

    /** `[count u8][len u16 BE][attestation]`; 144 = 0x0090. */
    @Test
    fun `golden - batch encoding of one attestation`() {
        val single = attestation().encode()!!.hexEncodedString()
        val expected = "01" + "0090" + single
        assertEquals(147, expected.length / 2)
        assertEquals(expected, VouchAttestation.encodeList(listOf(attestation()))!!.hexEncodedString())
    }

    @Test
    fun `round-trip - decode reverses encode`() {
        assertEquals(attestation(), VouchAttestation.decode(attestation().encode()!!))
    }

    @Test
    fun `round-trip - batch decode reverses encodeList`() {
        val list = listOf(attestation(), attestation())
        assertEquals(list, VouchAttestation.decodeList(VouchAttestation.encodeList(list)!!))
    }

    @Test
    fun `decode - unknown TLV type is skipped for forward compatibility`() {
        val withUnknown =
            "0120" + fingerprintHex +
                "7f03aabbcc" + // unknown type 0x7f, length 3
                "0220" + signingKeyHex +
                "0308" + timestampHex +
                "0440" + signatureHex
        assertEquals(attestation(), VouchAttestation.decode(bytes(withUnknown)))
    }

    @Test
    fun `decode - missing required field is rejected`() {
        val withoutSignature =
            "0120" + fingerprintHex + "0220" + signingKeyHex + "0308" + timestampHex
        assertNull(VouchAttestation.decode(bytes(withoutSignature)))
    }

    @Test
    fun `decode - truncated value is rejected`() {
        // Declares 32 bytes of fingerprint but supplies 4.
        assertNull(VouchAttestation.decode(bytes("0120aabbccdd")))
    }

    @Test
    fun `decode - dangling type byte without a length byte is rejected`() {
        assertNull(VouchAttestation.decode(bytes("0120" + fingerprintHex + "02")))
    }

    @Test
    fun `decode - wrong field length is rejected`() {
        // Fingerprint TLV declaring 4 bytes instead of 32.
        assertNull(VouchAttestation.decode(bytes("0104aabbccdd")))
    }

    @Test
    fun `encode - rejects wrong field sizes`() {
        val bad = VouchAttestation(ByteArray(31), ByteArray(32), 1uL, ByteArray(64))
        assertNull(bad.encode())
        assertNull(VouchAttestation(ByteArray(32), ByteArray(32), 1uL, ByteArray(63)).encode())
    }

    @Test
    fun `encodeList - rejects empty and oversized batches`() {
        assertNull(VouchAttestation.encodeList(emptyList()))
        assertNull(VouchAttestation.encodeList(List(17) { attestation() }))
        assertEquals(16, VouchAttestation.decodeList(VouchAttestation.encodeList(List(16) { attestation() })!!).size)
    }

    /** The sender-declared count is not trusted: at most MAX_BATCH_COUNT entries are parsed. */
    @Test
    fun `decodeList - caps at MAX_BATCH_COUNT regardless of declared count`() {
        val single = attestation().encode()!!.hexEncodedString()
        val payload = "ff" + (0 until 20).joinToString("") { "0090$single" }
        assertEquals(16, VouchAttestation.decodeList(bytes(payload)).size)
    }

    /** A declared count larger than the entries present simply yields what is there. */
    @Test
    fun `decodeList - over-declared count yields only the present entries`() {
        val single = attestation().encode()!!.hexEncodedString()
        assertEquals(2, VouchAttestation.decodeList(bytes("0a" + "0090$single".repeat(2))).size)
    }

    /** Malformed entries are dropped individually while the framing stays intact. */
    @Test
    fun `decodeList - drops a malformed entry and keeps the rest`() {
        val single = attestation().encode()!!.hexEncodedString()
        val garbage = "0004aabbccdd" // well-framed, undecodable (6 bytes)
        val payload = "03" + "0090$single" + "0006$garbage" + "0090$single"
        assertEquals(2, VouchAttestation.decodeList(bytes(payload)).size)
    }

    @Test
    fun `decodeList - truncated framing stops parsing`() {
        val single = attestation().encode()!!.hexEncodedString()
        // Second entry declares 144 bytes but supplies 2.
        assertEquals(1, VouchAttestation.decodeList(bytes("02" + "0090$single" + "0090aabb")).size)
    }

    @Test
    fun `decodeList - empty and count-only payloads yield nothing`() {
        assertEquals(0, VouchAttestation.decodeList(ByteArray(0)).size)
        assertEquals(0, VouchAttestation.decodeList(byteArrayOf(1)).size)
    }

    @Test
    fun `isExpired - inside the window`() {
        val now = timestampMs.toLong() + VouchAttestation.MAX_AGE_MS
        assertFalse(attestation().isExpired(now))
    }

    @Test
    fun `isExpired - past max age`() {
        val now = timestampMs.toLong() + VouchAttestation.MAX_AGE_MS + 1
        assertTrue(attestation().isExpired(now))
    }

    @Test
    fun `isExpired - future beyond clock skew`() {
        val now = timestampMs.toLong() - VouchAttestation.MAX_CLOCK_SKEW_MS
        assertFalse(attestation().isExpired(now))
        assertTrue(attestation().isExpired(now - 1))
    }

    @Test
    fun `isExpired - unrepresentable far-future timestamp`() {
        val far = VouchAttestation(bytes(fingerprintHex), bytes(signingKeyHex), ULong.MAX_VALUE, bytes(signatureHex))
        assertTrue(far.isExpired(timestampMs.toLong()))
    }

    @Test
    fun `build - rejects wrong key sizes and a bad signature length`() {
        val sign: (ByteArray) -> ByteArray = { ByteArray(64) }
        assertNull(VouchAttestation.build(ByteArray(31), ByteArray(32), 1uL, sign))
        assertNull(VouchAttestation.build(ByteArray(32), ByteArray(31), 1uL, sign))
        assertNull(VouchAttestation.build(ByteArray(32), ByteArray(32), 1uL) { ByteArray(63) })
        assertNull(VouchAttestation.build(ByteArray(32), ByteArray(32), 1uL) { null })
    }

    @Test
    fun `build - signs the canonical transcript`() {
        var signed: ByteArray? = null
        val built = VouchAttestation.build(bytes(fingerprintHex), bytes(signingKeyHex), timestampMs) {
            signed = it
            bytes(signatureHex)
        }
        assertEquals(attestation(), built)
        assertEquals(contextHex + fingerprintHex + signingKeyHex + timestampHex, signed!!.hexEncodedString())
    }

    @Test
    fun `verifySignature - passes the transcript and rejects malformed inputs`() {
        var seen: ByteArray? = null
        val ok = attestation().verifySignature(bytes(signingKeyHex)) { _, data, _ ->
            seen = data
            true
        }
        assertTrue(ok)
        assertEquals(attestation().signableBytes.hexEncodedString(), seen!!.hexEncodedString())

        // A voucher key of the wrong size never reaches the verifier.
        assertFalse(attestation().verifySignature(ByteArray(31)) { _, _, _ -> true })
    }
}
