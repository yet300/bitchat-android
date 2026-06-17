package com.app.transport.nostr

import com.app.common.serialization.JsonConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * NIP-17 seal authentication (iOS audit parity, upstream #709): a gift wrap is only accepted when
 * the seal is a validly-signed kind-13 event whose signer matches the inner rumor's author.
 */
class NostrProtocolSecurityTest {

    @Test
    fun decrypt_accepts_an_authenticated_seal() {
        val sender = NostrIdentity.generate()
        val recipient = NostrIdentity.generate()

        val giftWrap = NostrProtocol
            .createPrivateMessage("bitchat1:test", recipient.publicKeyHex, sender)
            .first()

        val decrypted = NostrProtocol.decryptPrivateMessage(giftWrap, recipient)

        assertEquals("bitchat1:test", decrypted?.first)
        assertEquals(sender.publicKeyHex, decrypted?.second)
    }

    @Test
    fun decrypt_rejects_a_seal_whose_signer_does_not_match_the_rumor_author() {
        val claimedSender = NostrIdentity.generate() // the rumor claims this author
        val attacker = NostrIdentity.generate()      // but the seal is signed by this key
        val recipient = NostrIdentity.generate()

        val giftWrap = forgedGiftWrap(
            content = "spoofed",
            claimedSender = claimedSender,
            sealSigner = attacker,
            recipient = recipient,
        )

        assertNull(NostrProtocol.decryptPrivateMessage(giftWrap, recipient))
    }

    /** Mirrors createSeal/createGiftWrap but lets the seal signer differ from the rumor author. */
    private fun forgedGiftWrap(
        content: String,
        claimedSender: NostrIdentity,
        sealSigner: NostrIdentity,
        recipient: NostrIdentity,
    ): NostrEvent {
        val rumorBase = NostrEvent(
            pubkey = claimedSender.publicKeyHex,
            createdAt = (System.currentTimeMillis() / 1000).toInt(),
            kind = NostrKind.DIRECT_MESSAGE,
            tags = listOf(listOf("p", recipient.publicKeyHex)),
            content = content,
        )
        val rumor = rumorBase.copy(id = rumorBase.computeEventIdHex())

        val seal = NostrEvent(
            pubkey = sealSigner.publicKeyHex,
            createdAt = NostrCrypto.randomizeTimestampUpToPast(),
            kind = NostrKind.SEAL,
            tags = emptyList(),
            content = NostrCrypto.encryptNIP44(
                plaintext = JsonConfig.json.encodeToString(NostrEvent.serializer(), rumor),
                recipientPublicKeyHex = recipient.publicKeyHex,
                senderPrivateKeyHex = sealSigner.privateKeyHex,
            ),
        ).sign(sealSigner.privateKeyHex)

        val (wrapPrivate, wrapPublic) = NostrCrypto.generateKeyPair()
        return NostrEvent(
            pubkey = wrapPublic,
            createdAt = NostrCrypto.randomizeTimestampUpToPast(),
            kind = NostrKind.GIFT_WRAP,
            tags = listOf(listOf("p", recipient.publicKeyHex)),
            content = NostrCrypto.encryptNIP44(
                plaintext = JsonConfig.json.encodeToString(NostrEvent.serializer(), seal),
                recipientPublicKeyHex = recipient.publicKeyHex,
                senderPrivateKeyHex = wrapPrivate,
            ),
        ).sign(wrapPrivate)
    }
}
