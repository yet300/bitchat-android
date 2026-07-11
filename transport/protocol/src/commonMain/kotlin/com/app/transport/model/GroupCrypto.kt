@file:OptIn(dev.whyoleg.cryptography.DelicateCryptographyApi::class)

package com.app.transport.model

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.ChaCha20Poly1305
import dev.whyoleg.cryptography.random.CryptographyRandom

/**
 * Seals and opens group message broadcasts ([GroupMessageEnvelope]).
 *
 * Body crypto is IETF ChaCha20-Poly1305 (RFC 8439) — byte-identical to the reference's CryptoKit
 * `ChaChaPoly`: a random 12-byte nonce, the cleartext `groupID | epoch` bound in as additional data,
 * and the 16-byte tag appended to the ciphertext. Sender authenticity is an Ed25519 signature over
 * `"bitchat-group-msg-v1" | groupID | epoch | messageID | timestamp | content` carried inside the
 * ciphertext, so a backfilled copy still authenticates after the sender's announce has expired.
 *
 * Signing/verifying are injected as lambdas (matching the reference's closure design) so this stays
 * inside the pure-wire `transport/protocol` module: [sealMessage]'s `sign` is Ed25519 over our own
 * Noise signing key; [openMessage]'s `verify` is `(publicKey, data, signature) -> Boolean`.
 */
object GroupCrypto {

    val MESSAGE_SIGNING_DOMAIN = "bitchat-group-msg-v1".encodeToByteArray()

    private val INNER_MESSAGE_ID: UByte = 0x01u
    private val INNER_SENDER_SIGNING_KEY: UByte = 0x02u
    private val INNER_SENDER_NICKNAME: UByte = 0x03u
    private val INNER_TIMESTAMP: UByte = 0x04u
    private val INNER_CONTENT: UByte = 0x05u
    private val INNER_SIGNATURE: UByte = 0x06u

    /**
     * Bytes the sender signs: `domain | groupID | epoch | messageID | timestamp | content`. Covering
     * the epoch stops a current member re-sealing another member's decrypted inner bytes under a
     * later epoch key.
     */
    fun messageSigningContent(groupID: ByteArray, epoch: UInt, messageID: String, timestampMs: ULong, content: String): ByteArray {
        val out = ArrayList<Byte>()
        MESSAGE_SIGNING_DOMAIN.forEach(out::add)
        groupID.forEach(out::add)
        GroupTlv.epochBytes(epoch).forEach(out::add)
        messageID.encodeToByteArray().forEach(out::add)
        GroupTlv.timestampBytes(timestampMs).forEach(out::add)
        content.encodeToByteArray().forEach(out::add)
        return out.toByteArray()
    }

    /**
     * Seals a group message: builds the signed inner TLV and encrypts it with the epoch key under a
     * fresh random nonce. Returns the encoded 0x25 envelope payload, or null when [sign] fails or the
     * inner TLV cannot be encoded.
     */
    fun sealMessage(
        content: String,
        messageID: String,
        senderNickname: String,
        senderSigningKey: ByteArray,
        timestampMs: ULong,
        groupID: ByteArray,
        epoch: UInt,
        key: ByteArray,
        sign: (ByteArray) -> ByteArray?,
    ): ByteArray? {
        val signingContent = messageSigningContent(groupID, epoch, messageID, timestampMs, content)
        val signature = sign(signingContent) ?: return null
        if (signature.size != BitchatGroup.SIGNATURE_LENGTH) return null

        val inner = ArrayList<Byte>()
        if (!GroupTlv.put(INNER_MESSAGE_ID, messageID.encodeToByteArray(), inner)) return null
        if (!GroupTlv.put(INNER_SENDER_SIGNING_KEY, senderSigningKey, inner)) return null
        if (!GroupTlv.put(INNER_SENDER_NICKNAME, senderNickname.encodeToByteArray(), inner)) return null
        if (!GroupTlv.put(INNER_TIMESTAMP, GroupTlv.timestampBytes(timestampMs), inner)) return null
        if (!GroupTlv.put(INNER_CONTENT, content.encodeToByteArray(), inner)) return null
        if (!GroupTlv.put(INNER_SIGNATURE, signature, inner)) return null

        val nonce = ByteArray(GroupMessageEnvelope.NONCE_LENGTH).also { CryptographyRandom.Default.nextBytes(it) }
        val ciphertext = aeadSeal(key, nonce, inner.toByteArray(), aad(groupID, epoch)) ?: return null
        return GroupMessageEnvelope(groupID, epoch, nonce, ciphertext).encode()
    }

    /**
     * Opens a group message envelope with the epoch key: decrypts, parses the inner TLV, and verifies
     * the sender's Ed25519 signature via [verify]. Roster membership of the sender is the CALLER's
     * check — this only proves the payload was authored by the embedded `senderSigningKey`. Returns
     * null on any decrypt/parse/signature failure.
     */
    fun openMessage(
        envelope: GroupMessageEnvelope,
        key: ByteArray,
        verify: (ByteArray, ByteArray, ByteArray) -> Boolean,
    ): GroupMessagePlaintext? {
        val inner = aeadOpen(key, envelope.nonce, envelope.ciphertext, aad(envelope.groupID, envelope.epoch)) ?: return null
        val fields = GroupTlv.parse(inner) ?: return null

        var messageID: String? = null
        var senderSigningKey: ByteArray? = null
        var senderNickname: String? = null
        var timestampMs: ULong? = null
        var content: String? = null
        var signature: ByteArray? = null
        for ((type, value) in fields) {
            when (type) {
                INNER_MESSAGE_ID -> messageID = value.decodeToString()
                INNER_SENDER_SIGNING_KEY -> if (value.size == BitchatGroup.SIGNING_KEY_LENGTH) senderSigningKey = value
                INNER_SENDER_NICKNAME -> senderNickname = value.decodeToString()
                INNER_TIMESTAMP -> timestampMs = GroupTlv.timestamp(value)
                INNER_CONTENT -> content = value.decodeToString()
                INNER_SIGNATURE -> if (value.size == BitchatGroup.SIGNATURE_LENGTH) signature = value
                // Unknown TLV: ignore.
            }
        }
        if (messageID.isNullOrEmpty() || senderSigningKey == null || senderNickname == null ||
            timestampMs == null || content == null || signature == null
        ) {
            return null
        }

        val signingContent = messageSigningContent(envelope.groupID, envelope.epoch, messageID, timestampMs, content)
        if (!verify(senderSigningKey, signingContent, signature)) return null

        return GroupMessagePlaintext(messageID, senderSigningKey, senderNickname, timestampMs, content)
    }

    /** Additional authenticated data binding a ciphertext to its group and epoch. */
    private fun aad(groupID: ByteArray, epoch: UInt): ByteArray = groupID + GroupTlv.epochBytes(epoch)

    private val cipher by lazy { CryptographyProvider.Default.get(ChaCha20Poly1305) }

    private fun chacha(key: ByteArray) =
        cipher.keyDecoder().decodeFromByteArrayBlocking(ChaCha20Poly1305.Key.Format.RAW, key).cipher()

    /** IETF ChaCha20-Poly1305 seal: returns `ciphertext || tag(16)`. Internal for the RFC 8439 KAT. */
    internal fun aeadSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray? =
        runCatching { chacha(key).encryptWithIvBlocking(nonce, plaintext, aad) }.getOrNull()

    /** IETF ChaCha20-Poly1305 open of a `ciphertext || tag(16)` body. Internal for the RFC 8439 KAT. */
    internal fun aeadOpen(key: ByteArray, nonce: ByteArray, body: ByteArray, aad: ByteArray): ByteArray? =
        runCatching { chacha(key).decryptWithIvBlocking(nonce, body, aad) }.getOrNull()
}
