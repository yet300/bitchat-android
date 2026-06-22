package com.app.crypto.noise

import com.app.crypto.hash.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Byte-parity proof for the channel cipher after moving off JCA to cryptography-kotlin.
 *
 * The [commitment] and [jcaBlob] vectors were captured from the previous
 * PBKDF2WithHmacSHA256 + AES/GCM/NoPadding implementation. Reproducing the same key
 * (commitment) proves PBKDF2 parity; decrypting the JCA-produced blob back to the original
 * plaintext proves the AES-256-GCM IV(12)||ciphertext||tag(16) wire format is unchanged.
 */
class ChannelCipherKatTest {

    private val password = "hunter2"
    private val channel = "#dev"
    private val commitment = "a714faf3355dabd7f455309339434eb8c3bf33474b83f41a7fdf14899ced5a7f"
    private val jcaBlob = "d2fa4d6165521477be4268acc5c71b4c2e3f50faeceaad1deba82b8010e96da7f1a14e60b9aee13f7f"

    private fun ByteArray.hex(): String =
        joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun String.unhex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun pbkdf2_key_is_byte_identical_to_jca() {
        val key = ChannelCipher.deriveKey(password, channel)
        assertEquals(commitment, Sha256.digest(key).hex())
    }

    @Test
    fun decrypts_a_blob_encrypted_by_jca() {
        val key = ChannelCipher.deriveKey(password, channel)
        assertEquals("parity-vector", ChannelCipher.decrypt(key, jcaBlob.unhex()).decodeToString())
    }

    @Test
    fun encrypt_then_decrypt_round_trips() {
        val key = ChannelCipher.deriveKey(password, channel)
        val blob = ChannelCipher.encrypt(key, "hello channel".encodeToByteArray())
        assertEquals("hello channel", ChannelCipher.decrypt(key, blob).decodeToString())
    }
}
