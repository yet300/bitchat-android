package com.app.transport.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-parity for Nostr secp256k1 after moving off BouncyCastle to secp256k1-kmp.
 *
 * [expectedXonly] and [bcSig] were captured from the previous BouncyCastle implementation. The
 * derived public key must be byte-identical, and a BouncyCastle-produced (i.e. standard BIP-340 =
 * iOS-compatible) Schnorr signature must verify under secp256k1-kmp. NIP-44 ECDH/HKDF parity is
 * covered separately by Nip44XChaChaParityTest.
 */
class Secp256k1ParityTest {

    private val priv = "01".repeat(32)
    private val expectedXonly = "1b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f"
    private val msgHash = "42".repeat(32).hexToByteArray()
    private val bcSig =
        "ef5110f45537acdc269e1444b424431aa1a860c28c32a9bdcfda8b43e3863389" +
            "25a7909231e3815b2a268715d81a91a9f794ff4a32412291de1dd73b14d9883c"

    @Test
    fun public_key_matches_bouncycastle() {
        assertEquals(expectedXonly, NostrCrypto.derivePublicKey(priv))
    }

    @Test
    fun verifies_a_bouncycastle_schnorr_signature() {
        assertTrue(NostrCrypto.schnorrVerify(msgHash, bcSig, expectedXonly))
    }

    @Test
    fun schnorr_sign_verify_round_trips() {
        val sig = NostrCrypto.schnorrSign(msgHash, priv)
        assertTrue(NostrCrypto.schnorrVerify(msgHash, sig, expectedXonly))
    }
}
