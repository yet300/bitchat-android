package com.app.crypto.hash

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Known-answer vectors (FIPS 180-4) pinning that [Sha256] produces standard SHA-256 output.
 * This is the byte-parity proof that swapping java.security.MessageDigest for the
 * cryptography-kotlin provider did not change identity-fingerprint / channel-commitment bytes.
 */
class Sha256KatTest {

    private fun ByteArray.hex(): String =
        joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun empty_input() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.digest(ByteArray(0)).hex(),
        )
    }

    @Test
    fun abc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.digest("abc".encodeToByteArray()).hex(),
        )
    }

    @Test
    fun long_input() {
        val msg = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.digest(msg.encodeToByteArray()).hex(),
        )
    }
}
