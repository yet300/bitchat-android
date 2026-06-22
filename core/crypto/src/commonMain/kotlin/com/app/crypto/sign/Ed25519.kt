package com.app.crypto.sign

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA

/**
 * Ed25519 signing for identity-announce signatures — iOS-wire compatible.
 *
 * Keys are raw 32-byte values (RAW format == the former BouncyCastle encoded form); signatures
 * are the standard 64-byte RFC 8032 form. Ed25519 is deterministic, so signatures are
 * byte-identical to the previous BouncyCastle implementation for the same key + message.
 *
 * Backed by the platform cryptography-kotlin provider (JDK on android, CryptoKit on apple).
 */
internal object Ed25519 {

    private val eddsa get() = CryptographyProvider.Default.get(EdDSA)
    private val curve = EdDSA.Curve.Ed25519

    /** Generates a key pair as (privateKey, publicKey), both raw 32-byte. */
    fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val keyPair = eddsa.keyPairGenerator(curve).generateKeyBlocking()
        val priv = keyPair.privateKey.encodeToByteArrayBlocking(EdDSA.PrivateKey.Format.RAW)
        val pub = keyPair.publicKey.encodeToByteArrayBlocking(EdDSA.PublicKey.Format.RAW)
        return priv to pub
    }

    /** Derives the raw 32-byte public key for a raw private key. */
    fun publicKeyOf(privateKey: ByteArray): ByteArray =
        eddsa.privateKeyDecoder(curve)
            .decodeFromByteArrayBlocking(EdDSA.PrivateKey.Format.RAW, privateKey)
            .getPublicKeyBlocking()
            .encodeToByteArrayBlocking(EdDSA.PublicKey.Format.RAW)

    /** Signs [data] with a raw private key, returning a 64-byte signature. */
    fun sign(privateKey: ByteArray, data: ByteArray): ByteArray =
        eddsa.privateKeyDecoder(curve)
            .decodeFromByteArrayBlocking(EdDSA.PrivateKey.Format.RAW, privateKey)
            .signatureGenerator()
            .generateSignatureBlocking(data)

    /**
     * Verifies a signature against [data] with a raw public key. Returns false (never throws) for a
     * malformed signature/key — some providers throw on an invalid curve point, matching the former
     * BouncyCastle behaviour of returning false.
     */
    fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            eddsa.publicKeyDecoder(curve)
                .decodeFromByteArrayBlocking(EdDSA.PublicKey.Format.RAW, publicKey)
                .signatureVerifier()
                .tryVerifySignatureBlocking(data, signature)
        }.getOrDefault(false)
}
