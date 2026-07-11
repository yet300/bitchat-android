package com.app.transport.model

import com.app.common.encoding.hexEncodedString
import com.app.transport.crypto.Sha256
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen wire vectors for private groups, derived by hand from the reference iOS
 * `Services/Groups/GroupProtocol.swift` — never captured from a run.
 *
 * All group TLVs use **1-byte** type + **2-byte big-endian** length (same as courier). The roster
 * blob and the two signing-content byte layouts are pinned so a drift on either breaks the build.
 */
class GroupWireGoldenTest {

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }

    private fun repeatHex(byte: String, count: Int) = byte.repeat(count)

    // groupID = 00..0f (16 bytes)
    private val groupIDHex = "000102030405060708090a0b0c0d0e0f"

    // Single roster member: fingerprint 0x01*32, signingKey 0x02*32, nickname "alice".
    private val fingerprintHex = repeatHex("01", 32)
    private val signingKeyHex = repeatHex("02", 32)
    private val aliceHex = "616c696365" // "alice"
    private val rosterHex = "01" + fingerprintHex + signingKeyHex + "05" + aliceHex

    private fun aliceMember() = GroupMember(
        fingerprint = bytes(fingerprintHex),
        signingKey = bytes(signingKeyHex),
        nickname = "alice",
    )

    // MARK: - Roster

    @Test
    fun `golden - roster blob encodes to the frozen bytes`() {
        val blob = GroupRosterCoding.encode(listOf(aliceMember()))!!
        assertEquals(rosterHex, blob.hexEncodedString())
    }

    @Test
    fun `roster round-trips`() {
        val decoded = GroupRosterCoding.decode(bytes(rosterHex))!!
        assertEquals(1, decoded.size)
        assertEquals("alice", decoded[0].nickname)
        assertEquals(fingerprintHex, decoded[0].fingerprint.hexEncodedString())
        assertEquals(signingKeyHex, decoded[0].signingKey.hexEncodedString())
    }

    @Test
    fun `roster rejects a member count over the cap`() {
        val tooMany = (0..BitchatGroup.MAX_MEMBERS).map { aliceMember() }
        assertNull(GroupRosterCoding.encode(tooMany))
    }

    @Test
    fun `roster nickname is truncated to 64 bytes on a UTF-8 boundary`() {
        // 40 'é' (2 bytes each = 80 bytes) — must trim to <=64 bytes, never mid-scalar.
        val member = GroupMember(bytes(fingerprintHex), bytes(signingKeyHex), "é".repeat(40))
        val decoded = GroupRosterCoding.decode(GroupRosterCoding.encode(listOf(member))!!)!!
        assertTrue(decoded[0].nickname.encodeToByteArray().size <= 64)
        // A clean boundary means every kept char is intact (32 'é' = 64 bytes).
        assertEquals("é".repeat(32), decoded[0].nickname)
    }

    // MARK: - GroupStatePayload

    @Test
    fun `golden - group state encodes to the frozen bytes`() {
        val keyHex = repeatHex("03", 32)
        val signatureHex = repeatHex("04", 64)
        val nameHex = "677270" // "grp"
        val expected =
            "01" + "0010" + groupIDHex +
                "02" + "0003" + nameHex +
                "03" + "0020" + keyHex +
                "04" + "0004" + "00000002" +
                "05" + "0047" + rosterHex + // roster blob length = 1+32+32+1+5 = 71 = 0x47
                "06" + "0020" + fingerprintHex +
                "07" + "0040" + signatureHex

        val payload = GroupStatePayload(
            groupID = bytes(groupIDHex),
            name = "grp",
            key = bytes(keyHex),
            epoch = 2u,
            members = listOf(aliceMember()),
            creatorFingerprint = bytes(fingerprintHex),
            signature = bytes(signatureHex),
        )
        assertEquals(expected, payload.encode()!!.hexEncodedString())
    }

    @Test
    fun `group state round-trips and skips an unknown TLV`() {
        val payload = GroupStatePayload(
            groupID = bytes(groupIDHex),
            name = "grp",
            key = bytes(repeatHex("03", 32)),
            epoch = 2u,
            members = listOf(aliceMember()),
            creatorFingerprint = bytes(fingerprintHex),
            signature = bytes(repeatHex("04", 64)),
        )
        val encoded = payload.encode()!!
        // Inject an unknown TLV (type 0x7f, len 2) at the end.
        val withUnknown = encoded + bytes("7f0002cafe")
        val decoded = GroupStatePayload.decode(withUnknown)!!
        assertEquals("grp", decoded.name)
        assertEquals(2u, decoded.epoch)
        assertContentEquals(payload.encode(), decoded.encode())
    }

    @Test
    fun `group state decode rejects missing required fields`() {
        // Only groupID present.
        assertNull(GroupStatePayload.decode(bytes("01" + "0010" + groupIDHex)))
    }

    @Test
    fun `golden - creator signing content layout`() {
        val key = bytes(repeatHex("03", 32))
        val rosterBlob = bytes(rosterHex)
        val content = GroupStatePayload.signingContent(bytes(groupIDHex), 2u, key, rosterBlob, "grp")

        val expected = GroupStatePayload.SIGNING_DOMAIN +
            bytes(groupIDHex) +
            bytes("00000002") +
            Sha256.digest(key) +
            Sha256.digest(rosterBlob) +
            Sha256.digest("grp".encodeToByteArray())
        assertContentEquals(expected, content)
        // domain(16) + groupID(16) + epoch(4) + 3*SHA256(32) = 132 bytes.
        assertEquals(16 + 16 + 4 + 96, content.size)
    }

    // MARK: - GroupMessageEnvelope

    @Test
    fun `golden - group message envelope encodes to the frozen bytes`() {
        val nonceHex = "202122232425262728292a2b"
        val ciphertextHex = "aabbccdd"
        val expected =
            "01" + "0010" + groupIDHex +
                "02" + "0004" + "00000001" +
                "03" + "000c" + nonceHex +
                "04" + "0004" + ciphertextHex

        val envelope = GroupMessageEnvelope(
            groupID = bytes(groupIDHex),
            epoch = 1u,
            nonce = bytes(nonceHex),
            ciphertext = bytes(ciphertextHex),
        )
        assertEquals(expected, envelope.encode()!!.hexEncodedString())
        assertEquals(envelope, GroupMessageEnvelope.decode(bytes(expected)))
    }

    @Test
    fun `envelope decode rejects a wrong-length nonce`() {
        // nonce TLV declares 8 bytes (not 12).
        val bad = "01" + "0010" + groupIDHex + "02" + "0004" + "00000001" +
            "03" + "0008" + "0011223344556677" + "04" + "0004" + "aabbccdd"
        assertNull(GroupMessageEnvelope.decode(bytes(bad)))
    }

    @Test
    fun `golden - message signing content layout`() {
        val content = GroupCrypto.messageSigningContent(bytes(groupIDHex), 3u, "mid-1", 0x0102030405060708uL, "hi")
        val expected = GroupCrypto.MESSAGE_SIGNING_DOMAIN +
            bytes(groupIDHex) +
            bytes("00000003") +
            "mid-1".encodeToByteArray() +
            bytes("0102030405060708") +
            "hi".encodeToByteArray()
        assertContentEquals(expected, content)
    }
}
