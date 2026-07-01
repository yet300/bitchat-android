package com.app.crypto.hash

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256

/**
 * SHA-256 for identity fingerprints and channel-key commitments. Output is standard SHA-256,
 * byte-identical across providers/platforms; callers format the hex (wire/UI) themselves.
 *
 * Backed by the platform cryptography-kotlin provider (JDK on android, CryptoKit on apple).
 */
object Sha256 {

    private val hasher by lazy { CryptographyProvider.Default.get(SHA256).hasher() }

    fun digest(data: ByteArray): ByteArray = hasher.hashBlocking(data)
}
