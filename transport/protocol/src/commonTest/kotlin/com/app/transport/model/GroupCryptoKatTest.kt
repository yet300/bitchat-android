package com.app.transport.model

import com.app.common.encoding.hexEncodedString
import com.app.transport.crypto.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Proves the group body cipher is IETF ChaCha20-Poly1305 (RFC 8439) — byte-identical to the
 * reference's CryptoKit `ChaChaPoly` — and that [GroupCrypto.sealMessage] / [openMessage] round-trip
 * with AAD binding and inner-signature gating.
 */
class GroupCryptoKatTest {

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }

    /** RFC 8439 §2.8.2 AEAD_CHACHA20_POLY1305 known-answer vector. */
    @Test
    fun `RFC 8439 ChaCha20-Poly1305 KAT`() {
        val key = bytes("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = bytes("070000004041424344454647")
        val aad = bytes("50515253c0c1c2c3c4c5c6c7")
        val plaintext = ("Ladies and Gentlemen of the class of '99: If I could offer you only one " +
            "tip for the future, sunscreen would be it.").encodeToByteArray()
        val expectedCiphertext =
            "d31a8d34648e60db7b86afbc53ef7ec2" +
                "a4aded51296e08fea9e2b5a736ee62d6" +
                "3dbea45e8ca9671282fafb69da92728b" +
                "1a71de0a9e060b2905d6a5b67ecd3b36" +
                "92ddbd7f2d778b8c9803aee328091b58" +
                "fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b" +
                "6116"
        val expectedTag = "1ae10b594f09e26a7e902ecbd0600691"

        val sealed = GroupCrypto.aeadSeal(key, nonce, plaintext, aad)!!
        assertEquals(expectedCiphertext + expectedTag, sealed.hexEncodedString())

        val opened = GroupCrypto.aeadOpen(key, nonce, sealed, aad)!!
        assertEquals(plaintext.hexEncodedString(), opened.hexEncodedString())
    }

    // Deterministic stand-ins for Ed25519 so the seal/open wiring is testable without keys.
    private val fakeSign: (ByteArray) -> ByteArray = { data -> Sha256.digest(data) + Sha256.digest(data) }
    private val fakeVerify: (ByteArray, ByteArray, ByteArray) -> Boolean = { _, data, sig ->
        sig.contentEquals(Sha256.digest(data) + Sha256.digest(data))
    }

    private val groupID = ByteArray(16) { it.toByte() }
    private val key = ByteArray(32) { 0x33 }
    private val senderSigningKey = ByteArray(32) { 0x44 }

    private fun seal(epoch: UInt = 5u) = GroupCrypto.sealMessage(
        content = "hello group",
        messageID = "msg-123",
        senderNickname = "alice",
        senderSigningKey = senderSigningKey,
        timestampMs = 1_700_000_000_000uL,
        groupID = groupID,
        epoch = epoch,
        key = key,
        sign = fakeSign,
    )

    @Test
    fun `seal then open round-trips the plaintext`() {
        val payload = seal()!!
        val envelope = GroupMessageEnvelope.decode(payload)!!
        assertEquals(5u, envelope.epoch)
        assertEquals(GroupMessageEnvelope.NONCE_LENGTH, envelope.nonce.size)

        val plaintext = GroupCrypto.openMessage(envelope, key, fakeVerify)
        assertNotNull(plaintext)
        assertEquals("msg-123", plaintext.messageID)
        assertEquals("alice", plaintext.senderNickname)
        assertEquals("hello group", plaintext.content)
        assertEquals(1_700_000_000_000uL, plaintext.timestampMs)
        assertEquals(senderSigningKey.hexEncodedString(), plaintext.senderSigningKey.hexEncodedString())
    }

    @Test
    fun `open fails when the epoch AAD is tampered`() {
        val envelope = GroupMessageEnvelope.decode(seal(epoch = 5u)!!)!!
        val tampered = GroupMessageEnvelope(envelope.groupID, epoch = 6u, envelope.nonce, envelope.ciphertext)
        assertNull(GroupCrypto.openMessage(tampered, key, fakeVerify))
    }

    @Test
    fun `open fails when the inner signature does not verify`() {
        val envelope = GroupMessageEnvelope.decode(seal()!!)!!
        assertNull(GroupCrypto.openMessage(envelope, key) { _, _, _ -> false })
    }

    @Test
    fun `open fails with the wrong key`() {
        val envelope = GroupMessageEnvelope.decode(seal()!!)!!
        val wrongKey = ByteArray(32) { 0x55 }
        assertNull(GroupCrypto.openMessage(envelope, wrongKey, fakeVerify))
    }
}
