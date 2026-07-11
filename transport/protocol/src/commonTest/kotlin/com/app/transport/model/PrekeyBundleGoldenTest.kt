package com.app.transport.model

import com.app.common.encoding.hexEncodedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen wire vectors for one-time prekey bundles (MessageType 0x24), derived by hand from the
 * reference iOS `BitFoundation/PrekeyBundle.swift` — never captured from a run.
 *
 * TLV entries carry a **1-byte type + 2-byte big-endian length** (contrast the vouch attestation,
 * which uses a 1-byte length).
 *
 * Signed transcript **length-prefixes** the context string (contrast vouch, which does not):
 *   `u8(24) | "bitchat-prekey-bundle-v1" | ownerKey(32) | u8(count) | (u32 BE id | pubkey(32))* |
 *    generatedAt(8 BE)`
 */
class PrekeyBundleGoldenTest {

    // 0x01..0x20
    private val ownerKeyHex = "0102030405060708090a0b0c0d0e0f10" + "1112131415161718191a1b1c1d1e1f20"

    // 0x21..0x40
    private val prekey5Hex = "2122232425262728292a2b2c2d2e2f30" + "3132333435363738393a3b3c3d3e3f40"

    // 0x41..0x60
    private val prekey6Hex = "4142434445464748494a4b4c4d4e4f50" + "5152535455565758595a5b5c5d5e5f60"

    // 0x81..0xc0
    private val signatureHex =
        "8182838485868788898a8b8c8d8e8f90" +
            "9192939495969798999a9b9c9d9e9fa0" +
            "a1a2a3a4a5a6a7a8a9aaabacadaeafb0" +
            "b1b2b3b4b5b6b7b8b9babbbcbdbebfc0"

    /** 0x0000019200000000 = 0x192 * 2^32 = 402 * 4294967296. */
    private val generatedAt: ULong = 1_726_576_852_992uL
    private val generatedAtHex = "0000019200000000"

    /** u8 length prefix 0x18 (=24), then "bitchat-prekey-bundle-v1" (24 ASCII bytes). */
    private val prefixedContextHex = "18" + "626974636861742d7072656b65792d62756e646c652d7631"

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }

    private fun bundle(prekeys: List<PrekeyBundle.Prekey> = twoPrekeys()) = PrekeyBundle(
        noiseStaticPublicKey = bytes(ownerKeyHex),
        prekeys = prekeys,
        generatedAt = generatedAt,
        signature = bytes(signatureHex),
    )

    private fun twoPrekeys() = listOf(
        PrekeyBundle.Prekey(5u, bytes(prekey5Hex)),
        PrekeyBundle.Prekey(6u, bytes(prekey6Hex)),
    )

    @Test
    fun `golden - constants match the reference`() {
        assertEquals("bitchat-prekey-bundle-v1", PrekeyBundle.SIGNING_CONTEXT)
        assertEquals(24, PrekeyBundle.SIGNING_CONTEXT.encodeToByteArray().size)
        assertEquals(32, PrekeyBundle.KEY_LENGTH)
        assertEquals(64, PrekeyBundle.SIGNATURE_LENGTH)
        assertEquals(8, PrekeyBundle.MAX_PREKEYS)
    }

    /** 1 + 24 + 32 + 1 + 2*36 + 8 = 138 bytes: prefixed context, owner key, count, entries, time. */
    @Test
    fun `golden - signable transcript bytes`() {
        val expected = prefixedContextHex +
            ownerKeyHex +
            "02" +
            "00000005" + prekey5Hex +
            "00000006" + prekey6Hex +
            generatedAtHex
        assertEquals(138, expected.length / 2)
        assertEquals(expected, bundle().signableBytes.hexEncodedString())
    }

    /** (3+32) + (3+72) + (3+8) + (3+64) = 188 bytes, TLV order 0x01,0x02,0x03,0x04. */
    @Test
    fun `golden - bundle encoding`() {
        val expected =
            "010020" + ownerKeyHex +
                "020048" + "00000005" + prekey5Hex + "00000006" + prekey6Hex +
                "030008" + generatedAtHex +
                "040040" + signatureHex
        assertEquals(188, expected.length / 2)
        assertEquals(expected, bundle().encode()!!.hexEncodedString())
    }

    /** A single-prekey bundle carries a 36-byte (0x0024) prekeys TLV. */
    @Test
    fun `golden - single prekey encoding`() {
        val single = bundle(listOf(PrekeyBundle.Prekey(5u, bytes(prekey5Hex))))
        val expected =
            "010020" + ownerKeyHex +
                "020024" + "00000005" + prekey5Hex +
                "030008" + generatedAtHex +
                "040040" + signatureHex
        assertEquals(expected, single.encode()!!.hexEncodedString())
    }

    @Test
    fun `round-trip - decode reverses encode`() {
        assertEquals(bundle(), PrekeyBundle.decode(bundle().encode()!!))
    }

    @Test
    fun `round-trip - full eight-prekey batch`() {
        val prekeys = (0u until 8u).map { PrekeyBundle.Prekey(it, ByteArray(32) { i -> (i + it.toInt()).toByte() }) }
        val full = bundle(prekeys)
        assertEquals(full, PrekeyBundle.decode(full.encode()!!))
    }

    @Test
    fun `decode - unknown TLV type is skipped for forward compatibility`() {
        val withUnknown =
            "010020" + ownerKeyHex +
                "7f0003aabbcc" + // unknown type 0x7f, length 3
                "020048" + "00000005" + prekey5Hex + "00000006" + prekey6Hex +
                "030008" + generatedAtHex +
                "040040" + signatureHex
        assertEquals(bundle(), PrekeyBundle.decode(bytes(withUnknown)))
    }

    @Test
    fun `decode - missing required field is rejected`() {
        val withoutSignature =
            "010020" + ownerKeyHex +
                "020048" + "00000005" + prekey5Hex + "00000006" + prekey6Hex +
                "030008" + generatedAtHex
        assertNull(PrekeyBundle.decode(bytes(withoutSignature)))
    }

    @Test
    fun `decode - empty prekeys TLV is rejected`() {
        val emptyPrekeys =
            "010020" + ownerKeyHex +
                "020000" +
                "030008" + generatedAtHex +
                "040040" + signatureHex
        assertNull(PrekeyBundle.decode(bytes(emptyPrekeys)))
    }

    @Test
    fun `decode - prekeys length not a multiple of 36 is rejected`() {
        val ragged =
            "010020" + ownerKeyHex +
                "020025" + "00000005" + prekey5Hex + "ff" +
                "030008" + generatedAtHex +
                "040040" + signatureHex
        assertNull(PrekeyBundle.decode(bytes(ragged)))
    }

    @Test
    fun `decode - more than eight prekeys is rejected`() {
        // 9 * 36 = 324 = 0x0144 bytes of entries.
        val entries = (0 until 9).joinToString("") { i ->
            "0000000$i" + prekey5Hex
        }
        val oversized =
            "010020" + ownerKeyHex +
                "020144" + entries +
                "030008" + generatedAtHex +
                "040040" + signatureHex
        assertNull(PrekeyBundle.decode(bytes(oversized)))
    }

    /** Duplicate prekey IDs would let one consumed ID shadow another. */
    @Test
    fun `decode - duplicate prekey IDs are rejected`() {
        val duplicated =
            "010020" + ownerKeyHex +
                "020048" + "00000005" + prekey5Hex + "00000005" + prekey6Hex +
                "030008" + generatedAtHex +
                "040040" + signatureHex
        assertNull(PrekeyBundle.decode(bytes(duplicated)))
    }

    @Test
    fun `decode - truncated value is rejected`() {
        assertNull(PrekeyBundle.decode(bytes("010020aabbccdd")))
    }

    @Test
    fun `decode - dangling type byte without a full length is rejected`() {
        assertNull(PrekeyBundle.decode(bytes("010020" + ownerKeyHex + "0200")))
    }

    @Test
    fun `decode - wrong owner key length is rejected`() {
        assertNull(PrekeyBundle.decode(bytes("010004aabbccdd")))
    }

    @Test
    fun `encode - rejects wrong sizes and prekey counts`() {
        assertNull(PrekeyBundle(ByteArray(31), twoPrekeys(), 1uL, ByteArray(64)).encode())
        assertNull(PrekeyBundle(ByteArray(32), twoPrekeys(), 1uL, ByteArray(63)).encode())
        assertNull(PrekeyBundle(ByteArray(32), emptyList(), 1uL, ByteArray(64)).encode())
        assertNull(
            PrekeyBundle(
                ByteArray(32),
                List(9) { PrekeyBundle.Prekey(it.toUInt(), ByteArray(32)) },
                1uL,
                ByteArray(64),
            ).encode(),
        )
        assertNull(
            PrekeyBundle(ByteArray(32), listOf(PrekeyBundle.Prekey(1u, ByteArray(31))), 1uL, ByteArray(64)).encode(),
        )
    }

    @Test
    fun `build - signs the canonical transcript`() {
        var signed: ByteArray? = null
        val built = PrekeyBundle.build(bytes(ownerKeyHex), twoPrekeys(), generatedAt) {
            signed = it
            bytes(signatureHex)
        }
        assertEquals(bundle(), built)
        assertEquals(bundle().signableBytes.hexEncodedString(), signed!!.hexEncodedString())
    }

    @Test
    fun `build - rejects wrong inputs and a bad signature length`() {
        assertNull(PrekeyBundle.build(ByteArray(31), twoPrekeys(), 1uL) { ByteArray(64) })
        assertNull(PrekeyBundle.build(ByteArray(32), emptyList(), 1uL) { ByteArray(64) })
        assertNull(PrekeyBundle.build(ByteArray(32), twoPrekeys(), 1uL) { ByteArray(63) })
        assertNull(PrekeyBundle.build(ByteArray(32), twoPrekeys(), 1uL) { null })
    }

    @Test
    fun `verifySignature - passes the transcript and rejects malformed inputs`() {
        var seen: ByteArray? = null
        val ok = bundle().verifySignature(bytes(ownerKeyHex)) { _, data, _ ->
            seen = data
            true
        }
        assertTrue(ok)
        assertEquals(bundle().signableBytes.hexEncodedString(), seen!!.hexEncodedString())

        // An owner key of the wrong size never reaches the verifier.
        assertFalse(bundle().verifySignature(ByteArray(31)) { _, _, _ -> true })
    }

    /** MessageType wire value pinned: prekeyBundle = 0x24 in the reference. */
    @Test
    fun `golden - MessageType PREKEY_BUNDLE is 0x24`() {
        assertEquals(0x24u.toUByte(), com.app.transport.protocol.MessageType.PREKEY_BUNDLE.value)
    }
}
