package com.app.transport.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256

/**
 * SHA-256 and HMAC-SHA256 primitives for the Nostr layer, backed by the multiplatform
 * cryptography-kotlin provider (JDK/BouncyCastle on android, CryptoKit on apple). These replace
 * java.security.MessageDigest and javax.crypto.Mac so the Nostr crypto can live in commonMain.
 *
 * Output is standard SHA-256 / RFC 2104 HMAC, byte-identical across providers and platforms.
 */
object Sha256 {

    private val hasher by lazy { CryptographyProvider.Default.get(SHA256).hasher() }

    fun digest(data: ByteArray): ByteArray = hasher.hashBlocking(data)
}

object HmacSha256 {

    private val hmac by lazy { CryptographyProvider.Default.get(HMAC) }

    /**
     * HMAC-SHA256(key, data). An all-zero key reproduces HKDF-extract with an empty salt (both pad
     * to a 64-byte zero block), matching the previous javax HmacSHA256 behaviour used by NIP-44.
     */
    fun mac(key: ByteArray, data: ByteArray): ByteArray =
        hmac.keyDecoder(SHA256)
            .decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, key)
            .signatureGenerator()
            .generateSignatureBlocking(data)
}
