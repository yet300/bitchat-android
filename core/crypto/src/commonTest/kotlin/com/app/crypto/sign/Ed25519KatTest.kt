package com.app.crypto.sign

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Byte-parity proof for Ed25519 announce signatures after moving off BouncyCastle to
 * cryptography-kotlin. [pubHex] and [sigHex] were captured from the BouncyCastle implementation
 * for the fixed [seed] and [msg]. Reproducing the identical public key and (deterministic, RFC 8032)
 * signature proves the on-wire signing/verification bytes are unchanged — iOS still verifies them.
 */
class Ed25519KatTest {

    private val seed = ByteArray(32) { it.toByte() }
    private val msg = "bitchat-announce".encodeToByteArray()
    private val pubHex = "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8"
    private val sigHex =
        "2d418e670d05111acd3c9cb629115dbe22bbeb699ade7eb2fcc4f63714e78054" +
            "b225b825bd6e232279624edca566aa28744238a2104c9d05d43b90ab89c05b01"

    private fun ByteArray.hex(): String =
        joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun String.unhex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun public_key_matches_bouncycastle() {
        assertEquals(pubHex, Ed25519.publicKeyOf(seed).hex())
    }

    @Test
    fun signature_is_byte_identical_to_bouncycastle() {
        assertEquals(sigHex, Ed25519.sign(seed, msg).hex())
    }

    @Test
    fun verifies_a_bouncycastle_signature() {
        assertTrue(Ed25519.verify(pubHex.unhex(), msg, sigHex.unhex()))
    }

    @Test
    fun rejects_a_tampered_signature() {
        val bad = sigHex.unhex().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(Ed25519.verify(pubHex.unhex(), msg, bad))
    }

    @Test
    fun generated_keypair_round_trips() {
        val (priv, pub) = Ed25519.generateKeyPair()
        assertTrue(Ed25519.verify(pub, msg, Ed25519.sign(priv, msg)))
        assertEquals(pub.hex(), Ed25519.publicKeyOf(priv).hex())
    }
}
