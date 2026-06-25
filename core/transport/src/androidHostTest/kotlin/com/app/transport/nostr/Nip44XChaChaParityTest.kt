package com.app.transport.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Byte-parity for NIP-44 v2 after moving XChaCha20-Poly1305 off Tink to HChaCha20 +
 * cryptography-kotlin ChaCha20-Poly1305. The ciphertext was produced by the previous Tink-backed
 * implementation for fixed keys; the new AEAD must decrypt it to the original plaintext, proving the
 * XChaCha20-Poly1305 construction (nonce24 || ct || tag) is byte-compatible (iOS NIP-44 interop).
 */
class Nip44XChaChaParityTest {

    private val recipientPriv = "0202020202020202020202020202020202020202020202020202020202020202"
    private val senderPub = "1b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f"
    private val tinkCiphertext =
        "v2:FR0gBmj8oKCGD_Zr9I1dm4dWC82NbqFbQWA5S33RHa406dY6nWQaOIAhWohghDN3Dy_TRAv2N2hocgs"

    @Test
    fun decrypts_a_tink_produced_nip44_v2_ciphertext() {
        assertEquals(
            "nip44 parity vector",
            NostrCrypto.decryptNIP44(tinkCiphertext, senderPub, recipientPriv),
        )
    }

    @Test
    fun encrypt_then_decrypt_round_trips() {
        val senderPriv = "03".repeat(32)
        val recipPriv = "04".repeat(32)
        val recipPub = NostrCrypto.derivePublicKey(recipPriv)
        val senderPub2 = NostrCrypto.derivePublicKey(senderPriv)
        val ct = NostrCrypto.encryptNIP44("hello nip44 round-trip", recipPub, senderPriv)
        assertEquals("hello nip44 round-trip", NostrCrypto.decryptNIP44(ct, senderPub2, recipPriv))
    }
}
