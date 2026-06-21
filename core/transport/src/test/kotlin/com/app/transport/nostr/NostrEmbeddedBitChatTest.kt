package com.app.transport.nostr

import com.app.transport.model.NoisePayloadType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The embedded `bitchat1:` codec must round-trip: what [NostrEmbeddedBitChat] encodes for an
 * outgoing Nostr DM, its new [NostrEmbeddedBitChat.decode] counterpart must recover (sender peerID,
 * type, message id, content) — the receive path the Phase-D deletion left missing.
 */
class NostrEmbeddedBitChatTest {

    @Test
    fun private_message_round_trips() {
        val senderPeerID = "0011223344556677"
        val encoded = NostrEmbeddedBitChat.encodePMForNostr(
            content = "hello over nostr",
            messageID = "MSG-1",
            recipientPeerID = "8899aabbccddeeff",
            senderPeerID = senderPeerID,
        )!!

        val decoded = NostrEmbeddedBitChat.decode(encoded)!!

        assertEquals(NoisePayloadType.PRIVATE_MESSAGE, decoded.type)
        assertEquals(senderPeerID, decoded.senderPeerID)
        assertEquals("MSG-1", decoded.messageID)
        assertEquals("hello over nostr", decoded.content)
    }

    @Test
    fun delivery_ack_round_trips() {
        val senderPeerID = "0011223344556677"
        val encoded = NostrEmbeddedBitChat.encodeAckForNostr(
            type = NoisePayloadType.DELIVERED,
            messageID = "MSG-2",
            recipientPeerID = "8899aabbccddeeff",
            senderPeerID = senderPeerID,
        )!!

        val decoded = NostrEmbeddedBitChat.decode(encoded)!!

        assertEquals(NoisePayloadType.DELIVERED, decoded.type)
        assertEquals(senderPeerID, decoded.senderPeerID)
        assertEquals("MSG-2", decoded.messageID)
        assertNull(decoded.content)
    }

    @Test
    fun read_receipt_no_recipient_round_trips() {
        val senderPeerID = "0011223344556677"
        val encoded = NostrEmbeddedBitChat.encodeAckForNostrNoRecipient(
            type = NoisePayloadType.READ_RECEIPT,
            messageID = "MSG-3",
            senderPeerID = senderPeerID,
        )!!

        val decoded = NostrEmbeddedBitChat.decode(encoded)!!

        assertEquals(NoisePayloadType.READ_RECEIPT, decoded.type)
        assertEquals("MSG-3", decoded.messageID)
    }

    @Test
    fun rejects_non_bitchat_payload() {
        assertNull(NostrEmbeddedBitChat.decode("not-a-bitchat-string"))
    }
}
