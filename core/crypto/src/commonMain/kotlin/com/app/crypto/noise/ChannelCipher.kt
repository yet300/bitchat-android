package com.app.crypto.noise

import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.PBKDF2
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.io.bytestring.ByteString

/**
 * Password-protected channel cipher primitive — iOS-wire compatible.
 *
 * Key = PBKDF2-HMAC-SHA256(password, salt = channel-name UTF-8, 100_000 iterations, 256-bit).
 * Wire blob = AES-256-GCM with a random 12-byte IV prepended and a 128-bit tag appended:
 * IV(12) || ciphertext || tag(16) — byte-identical to the former JCA implementation.
 */
internal object ChannelCipher {

    private const val PBKDF2_ITERATIONS = 100_000
    private const val KEY_SIZE_BYTES = 32

    private val provider get() = CryptographyProvider.Default

    /** PBKDF2-derived 256-bit AES key for [password] in [channel]. */
    fun deriveKey(password: String, channel: String): ByteArray =
        provider.get(PBKDF2)
            .secretDerivation(
                digest = SHA256,
                iterations = PBKDF2_ITERATIONS,
                outputSize = KEY_SIZE_BYTES.bytes,
                salt = ByteString(*channel.encodeToByteArray()),
            )
            .deriveSecretBlocking(password.encodeToByteArray())
            .toByteArray()

    private fun cipher(key: ByteArray) =
        provider.get(AES.GCM)
            .keyDecoder()
            .decodeFromByteArrayBlocking(AES.Key.Format.RAW, key)
            .cipher()

    /** Encrypts [plaintext], returning IV(12) || ciphertext || tag(16). */
    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray =
        cipher(key).encryptBlocking(plaintext)

    /** Decrypts an IV(12) || ciphertext || tag(16) blob. */
    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray =
        cipher(key).decryptBlocking(blob)
}
