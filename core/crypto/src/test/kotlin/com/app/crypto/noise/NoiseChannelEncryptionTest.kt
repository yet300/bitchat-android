package com.app.crypto.noise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The recovered channel password primitive (PBKDF2 + AES-256-GCM) must round-trip an encrypted
 * message and produce a stable, password-bound key commitment that ChannelRepositoryImpl verifies
 * a join password against.
 */
class NoiseChannelEncryptionTest {

    @Test
    fun encrypts_and_decrypts_a_channel_message() {
        val enc = NoiseChannelEncryption()
        enc.setChannelPassword("hunter2", "#dev")

        val cipherText = enc.encryptChannelMessage("secret channel text", "#dev")
        val plain = enc.decryptChannelMessage(cipherText, "#dev")

        assertEquals("secret channel text", plain)
    }

    @Test
    fun a_matching_password_reproduces_the_same_key_commitment() {
        val creator = NoiseChannelEncryption().apply { setChannelPassword("hunter2", "#dev") }
        val joiner = NoiseChannelEncryption().apply { setChannelPassword("hunter2", "#dev") }

        assertEquals(creator.calculateKeyCommitment("#dev"), joiner.calculateKeyCommitment("#dev"))
    }

    @Test
    fun a_wrong_password_yields_a_different_commitment() {
        val creator = NoiseChannelEncryption().apply { setChannelPassword("hunter2", "#dev") }
        val wrong = NoiseChannelEncryption().apply { setChannelPassword("not-it", "#dev") }

        assertNotEquals(creator.calculateKeyCommitment("#dev"), wrong.calculateKeyCommitment("#dev"))
    }

    @Test
    fun no_commitment_without_a_key() {
        assertNull(NoiseChannelEncryption().calculateKeyCommitment("#dev"))
    }
}
