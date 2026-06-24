package com.app.crypto.sign

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidEd25519ProviderTest {

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
    fun android_runtime_uses_bouncycastle_backed_jdk_provider_for_ed25519() {
        assertNotNull(Class.forName("dev.whyoleg.cryptography.providers.jdk.bc.BcDefaultJdkSecurityProvider"))

        assertEquals(pubHex, Ed25519.publicKeyOf(seed).hex())
        assertEquals(sigHex, Ed25519.sign(seed, msg).hex())
        assertTrue(Ed25519.verify(pubHex.unhex(), msg, sigHex.unhex()))
    }
}
