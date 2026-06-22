package com.app.transport.nostr

import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic utilities for the Nostr protocol: secp256k1 key handling, BIP-340 Schnorr (NIP-01)
 * and NIP-44 v2 encryption. Backed by ACINQ secp256k1-kmp (libsecp256k1) — replaces the former
 * hand-rolled BouncyCastle EC math + Schnorr. Wire-compatible with iOS (NIP-01 signatures verify;
 * NIP-44 shared secret + HKDF + XChaCha20-Poly1305 unchanged).
 */
internal object NostrCrypto {

    private val secureRandom = SecureRandom()

    /** Generate a secp256k1 key pair as (privateKeyHex, x-only publicKeyHex). */
    fun generateKeyPair(): Pair<String, String> {
        var priv: ByteArray
        do {
            priv = ByteArray(32).also { secureRandom.nextBytes(it) }
        } while (!Secp256k1.secKeyVerify(priv))
        return priv.toHexString() to xOnlyPublicKey(priv).toHexString()
    }

    /** Derive the x-only (32-byte) public key from a private key. */
    fun derivePublicKey(privateKeyHex: String): String =
        xOnlyPublicKey(privateKeyHex.hexToByteArray()).toHexString()

    private fun xOnlyPublicKey(priv: ByteArray): ByteArray =
        Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(priv)).copyOfRange(1, 33)

    /** Validate a 32-byte secp256k1 private key. */
    fun isValidPrivateKey(privateKeyHex: String): Boolean = try {
        val p = privateKeyHex.hexToByteArray()
        p.size == 32 && Secp256k1.secKeyVerify(p)
    } catch (_: Exception) {
        false
    }

    /** Validate a 32-byte x-only public key (x must be a valid curve coordinate). */
    fun isValidPublicKey(publicKeyHex: String): Boolean = try {
        val x = publicKeyHex.hexToByteArray()
        x.size == 32 && run { Secp256k1.pubkeyParse(liftCandidate(x, oddY = false)); true }
    } catch (_: Exception) {
        false
    }

    // ------------------------------------------------------------------------------------------
    // NIP-44 v2
    // ------------------------------------------------------------------------------------------

    /** NIP-44 v2 encryption -> "v2:" + base64url(nonce24 || ciphertext || tag). */
    fun encryptNIP44(plaintext: String, recipientPublicKeyHex: String, senderPrivateKeyHex: String): String {
        // iOS derives the HKDF input from the compressed shared point (33 bytes), even-Y lift.
        val secretMaterial = sharedPointCompressed(senderPrivateKeyHex, recipientPublicKeyHex, oddY = false)
        val key = deriveNIP44Key(secretMaterial)
        val combined = XChaCha20Poly1305Aead(key).encrypt(plaintext.toByteArray(Charsets.UTF_8), null)
        return "v2:" + base64UrlNoPad(combined)
    }

    /** NIP-44 v2 decryption. Only accepts "v2:" base64url; tries both x-only parities. */
    fun decryptNIP44(ciphertext: String, senderPublicKeyHex: String, recipientPrivateKeyHex: String): String {
        require(ciphertext.startsWith("v2:")) { "Invalid NIP-44 version prefix" }
        val data = base64UrlDecode(ciphertext.substring(3))
            ?: throw IllegalArgumentException("Invalid base64url payload")
        var lastError: Exception? = null
        for (oddY in listOf(false, true)) {
            try {
                val secretMaterial = sharedPointCompressed(recipientPrivateKeyHex, senderPublicKeyHex, oddY)
                val key = deriveNIP44Key(secretMaterial)
                return String(XChaCha20Poly1305Aead(key).decrypt(data, null), Charsets.UTF_8)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw RuntimeException("NIP-44 v2 decryption failed: ${lastError?.message}", lastError)
    }

    /** Compressed (33-byte) ECDH shared point for an x-only peer key with the chosen Y parity. */
    private fun sharedPointCompressed(privateKeyHex: String, peerXonlyHex: String, oddY: Boolean): ByteArray {
        val priv = privateKeyHex.hexToByteArray()
        val candidate = liftCandidate(peerXonlyHex.hexToByteArray(), oddY)
        val shared = Secp256k1.pubKeyTweakMul(candidate, priv) // 65-byte uncompressed shared point
        return Secp256k1.pubKeyCompress(shared)                // 33-byte compressed
    }

    private fun liftCandidate(xOnly: ByteArray, oddY: Boolean): ByteArray {
        require(xOnly.size == 32) { "X-only public key must be 32 bytes" }
        val compressed = ByteArray(33)
        compressed[0] = if (oddY) 0x03 else 0x02
        xOnly.copyInto(compressed, 1)
        return compressed
    }

    /** NIP-44 v2 key derivation: HKDF-SHA256(salt = empty, info = "nip44-v2", length = 32). */
    private fun deriveNIP44Key(sharedSecret: ByteArray): ByteArray {
        // HKDF-extract with an empty salt == HMAC with a zero key (both pad to a 64-byte zero block).
        val prk = hmacSha256(ByteArray(32), sharedSecret)
        // HKDF-expand, single block (T(1) = HMAC(prk, info || 0x01)).
        return hmacSha256(prk, "nip44-v2".toByteArray(Charsets.UTF_8) + byteArrayOf(0x01)).copyOf(32)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    // ------------------------------------------------------------------------------------------
    // BIP-340 Schnorr (NIP-01)
    // ------------------------------------------------------------------------------------------

    /** BIP-340 Schnorr signature over a 32-byte message hash; returns 64-byte (r||s) hex. */
    fun schnorrSign(messageHash: ByteArray, privateKeyHex: String): String {
        require(messageHash.size == 32) { "Message hash must be 32 bytes" }
        val priv = privateKeyHex.hexToByteArray()
        require(priv.size == 32) { "Private key must be 32 bytes" }
        val auxRand = ByteArray(32).also { secureRandom.nextBytes(it) }
        return Secp256k1.signSchnorr(messageHash, priv, auxRand).toHexString()
    }

    /** BIP-340 Schnorr verification (message hash 32, signature 64, x-only public key 32). */
    fun schnorrVerify(messageHash: ByteArray, signatureHex: String, publicKeyHex: String): Boolean = try {
        val sig = signatureHex.hexToByteArray()
        val pub = publicKeyHex.hexToByteArray()
        messageHash.size == 32 && sig.size == 64 && pub.size == 32 &&
            Secp256k1.verifySchnorr(sig, messageHash, pub)
    } catch (_: Exception) {
        false
    }

    // ------------------------------------------------------------------------------------------
    // Timestamps (privacy)
    // ------------------------------------------------------------------------------------------

    /** Random timestamp offset for privacy (±15 minutes). */
    fun randomizeTimestamp(baseTimestamp: Long = System.currentTimeMillis() / 1000): Int {
        val offset = secureRandom.nextInt(1800) - 900
        return (baseTimestamp + offset).toInt()
    }

    /** Random timestamp up to maxPastSeconds in the past (default 2 days). */
    fun randomizeTimestampUpToPast(maxPastSeconds: Int = 172800): Int {
        val now = (System.currentTimeMillis() / 1000).toInt()
        val offset = if (maxPastSeconds > 0) secureRandom.nextInt(maxPastSeconds + 1) else 0
        return now - offset
    }

    // ------------------------------------------------------------------------------------------
    // base64url (no padding)
    // ------------------------------------------------------------------------------------------

    private fun base64UrlNoPad(data: ByteArray): String {
        val b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        return b64.replace('+', '-').replace('/', '_').replace("=", "")
    }

    private fun base64UrlDecode(s: String): ByteArray? {
        var str = s.replace('-', '+').replace('_', '/')
        val pad = (4 - (str.length % 4)) % 4
        if (pad > 0) str += "=".repeat(pad)
        return try {
            android.util.Base64.decode(str, android.util.Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
