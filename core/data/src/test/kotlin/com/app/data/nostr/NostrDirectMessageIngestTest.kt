@file:OptIn(ExperimentalCoroutinesApi::class)

package com.app.data.nostr

import android.content.Context
import com.app.data.favorites.FavoritesPersistenceService
import com.app.domain.model.ConversationId
import com.app.domain.model.GeoPerson
import com.app.domain.repository.GeohashRepository
import com.app.transport.IncomingMessageSink
import com.app.transport.SeenMessageStore
import com.app.transport.model.BitchatMessage
import com.app.transport.nostr.NostrEmbeddedBitChat
import com.app.transport.nostr.NostrIdentity
import com.app.transport.nostr.NostrIdentityBridge
import com.app.transport.nostr.NostrProtocol
import com.app.transport.nostr.NostrRelayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

class NostrDirectMessageIngestTest {

    private class RecordingSink : IncomingMessageSink {
        val privates = mutableListOf<Pair<String, BitchatMessage>>()
        val deliveryAcks = mutableListOf<Pair<String, String>>()
        val readReceipts = mutableListOf<Pair<String, String>>()
        override fun setPeers(peerIds: List<String>) = Unit
        override fun addPublicMessage(message: BitchatMessage) = Unit
        override fun addPrivateMessage(peerId: String, message: BitchatMessage) { privates += peerId to message }
        override fun addChannelMessage(channel: String, message: BitchatMessage) = Unit
        override fun onDeliveryAck(messageId: String, peerId: String) { deliveryAcks += messageId to peerId }
        override fun onReadReceipt(messageId: String, peerId: String) { readReceipts += messageId to peerId }
    }

    private class FakeGeohashRepository(private val blocked: Set<String> = emptySet()) : GeohashRepository {
        override fun observeParticipants(): Flow<List<GeoPerson>> = MutableStateFlow(emptyList())
        override fun observeSelectedChannel(): Flow<ConversationId> = MutableStateFlow(ConversationId.PublicMesh)
        override fun observeParticipantCounts(): Flow<Map<String, Int>> = MutableStateFlow(emptyMap())
        override suspend fun select(channel: ConversationId) = Unit
        override suspend fun startDirectMessage(pubkeyHex: String): ConversationId = ConversationId.PublicMesh
        override suspend fun setBlocked(pubkeyHex: String, blocked: Boolean) = Unit
        override suspend fun isUserBlocked(pubkeyHex: String): Boolean = pubkeyHex.lowercase() in blocked
        override suspend fun isTeleported(pubkeyHex: String): Boolean = false
        override suspend fun pubkeyForNickname(nickname: String): String? = null
        override suspend fun pubkeyForShortId(shortId: String): String? = null
    }

    private fun ingest(
        sink: IncomingMessageSink,
        geohash: GeohashRepository,
    ) = NostrDirectMessageIngest(
        context = mock<Context>(),
        relayManager = mock<NostrRelayManager>(),
        nostrIdentityBridge = mock<NostrIdentityBridge>(),
        sink = sink,
        seenMessageStore = mock<SeenMessageStore>(),
        favoritesService = mock<FavoritesPersistenceService>(),
        nostrMessageSender = mock<NostrMessageSender>(),
        geohashRepository = geohash,
        aliasRegistry = mock(),
        conversationRegistry = mock(),
        scope = CoroutineScope(UnconfinedTestDispatcher()),
    )

    private fun giftWrapPM(sender: NostrIdentity, recipient: NostrIdentity, content: String, messageId: String) =
        NostrProtocol.createPrivateMessage(
            content = NostrEmbeddedBitChat.encodePMForNostr(
                content = content,
                messageID = messageId,
                recipientPeerID = "8899aabbccddeeff",
                senderPeerID = "0011223344556677",
            )!!,
            recipientPubkey = recipient.publicKeyHex,
            senderIdentity = sender,
        ).first()

    @Test
    fun incoming_gift_wrap_is_decrypted_decoded_and_added_to_the_private_timeline() {
        val sender = NostrIdentity.generate()
        val recipient = NostrIdentity.generate()
        val sink = RecordingSink()

        ingest(sink, FakeGeohashRepository())
            .onGiftWrap(giftWrapPM(sender, recipient, "hi from nostr", "MID-1"), geohash = "", identity = recipient)

        assertEquals(1, sink.privates.size)
        val (convKey, message) = sink.privates.single()
        assertEquals("nostr_${sender.publicKeyHex.take(16)}", convKey)
        assertEquals("hi from nostr", message.content)
        assertEquals("MID-1", message.id)
        assertTrue(message.isPrivate)
    }

    @Test
    fun a_blocked_geohash_sender_is_dropped() {
        val sender = NostrIdentity.generate()
        val recipient = NostrIdentity.generate()
        val sink = RecordingSink()

        ingest(sink, FakeGeohashRepository(blocked = setOf(sender.publicKeyHex.lowercase())))
            .onGiftWrap(giftWrapPM(sender, recipient, "blocked", "MID-2"), geohash = "geohash1", identity = recipient)

        assertTrue(sink.privates.isEmpty())
    }
}
