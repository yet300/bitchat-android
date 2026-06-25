@file:OptIn(dev.whyoleg.cryptography.DelicateCryptographyApi::class)

package com.app.transport.nostr

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.ChaCha20Poly1305
import dev.whyoleg.cryptography.random.CryptographyRandom

/**
 * XChaCha20-Poly1305 (NIP-44 v2) — wire-compatible replacement for Tink's XChaCha20Poly1305.
 *
 * XChaCha20 = HChaCha20(key, nonce[0:16]) -> subkey, then IETF ChaCha20-Poly1305 with the 12-byte
 * nonce (0x00000000 || nonce[16:24]) via cryptography-kotlin. Output format is byte-identical to
 * Tink: nonce(24) || ciphertext || tag(16). HChaCha20 is the standard ChaCha core with no final
 * state addition — pinned by a decrypt KAT against a Tink-produced ciphertext.
 */
internal class XChaCha20Poly1305Aead(private val key: ByteArray) {

    init {
        require(key.size == 32) { "XChaCha20 key must be 32 bytes" }
    }

    /** Encrypts to nonce(24) || ciphertext || tag(16). */
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
        val nonce24 = ByteArray(24).also { random.nextBytes(it) }
        val body = cipher(hChaCha20(key, nonce24))
            .encryptWithIvBlocking(chachaNonce(nonce24), plaintext, associatedData ?: EMPTY)
        return nonce24 + body
    }

    /** Decrypts a nonce(24) || ciphertext || tag(16) blob. */
    fun decrypt(combined: ByteArray, associatedData: ByteArray?): ByteArray {
        require(combined.size >= 24 + 16) { "XChaCha20 ciphertext too short" }
        val nonce24 = combined.copyOfRange(0, 24)
        val body = combined.copyOfRange(24, combined.size)
        return cipher(hChaCha20(key, nonce24))
            .decryptWithIvBlocking(chachaNonce(nonce24), body, associatedData ?: EMPTY)
    }

    private fun cipher(subkey: ByteArray) =
        CryptographyProvider.Default.get(ChaCha20Poly1305)
            .keyDecoder()
            .decodeFromByteArrayBlocking(ChaCha20Poly1305.Key.Format.RAW, subkey)
            .cipher()

    private fun chachaNonce(nonce24: ByteArray): ByteArray = ByteArray(4) + nonce24.copyOfRange(16, 24)

    private companion object {
        private val random = CryptographyRandom.Default
        private val EMPTY = ByteArray(0)

        // HChaCha20 of a 32-byte key and 16-byte nonce -> 32-byte subkey.
        fun hChaCha20(key: ByteArray, nonce24: ByteArray): ByteArray {
            val s = IntArray(16)
            s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
            for (i in 0 until 8) s[4 + i] = leInt(key, i * 4)
            for (i in 0 until 4) s[12 + i] = leInt(nonce24, i * 4)
            repeat(10) {
                qr(s, 0, 4, 8, 12); qr(s, 1, 5, 9, 13); qr(s, 2, 6, 10, 14); qr(s, 3, 7, 11, 15)
                qr(s, 0, 5, 10, 15); qr(s, 1, 6, 11, 12); qr(s, 2, 7, 8, 13); qr(s, 3, 4, 9, 14)
            }
            val out = ByteArray(32)
            val words = intArrayOf(s[0], s[1], s[2], s[3], s[12], s[13], s[14], s[15])
            for (i in words.indices) leWrite(out, i * 4, words[i])
            return out
        }

        fun qr(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
            s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 16)
            s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 12)
            s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 8)
            s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 7)
        }

        fun rotl(x: Int, n: Int) = (x shl n) or (x ushr (32 - n))

        fun leInt(b: ByteArray, o: Int) =
            (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
                ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

        fun leWrite(b: ByteArray, o: Int, v: Int) {
            b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte()
            b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte()
        }
    }
}
