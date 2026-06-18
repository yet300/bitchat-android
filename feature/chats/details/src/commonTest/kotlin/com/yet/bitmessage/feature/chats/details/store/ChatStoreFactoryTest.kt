@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package com.yet.bitmessage.feature.chats.details.store

import com.app.domain.model.Attachment
import com.app.domain.model.BitMessage
import com.app.domain.model.Conversation
import com.app.domain.model.ConversationId
import com.app.domain.model.DeliveryStatus
import com.app.domain.model.Fingerprint
import com.app.domain.model.GeohashChannel
import com.app.domain.model.GeohashLevel
import com.app.domain.model.MyIdentity
import com.app.domain.model.Peer
import com.app.domain.model.PeerId
import com.app.domain.model.PeerIdentity
import com.app.domain.model.Reachability
import com.app.domain.model.SenderRef
import com.app.domain.model.Channel
import com.app.domain.model.Contact
import com.app.domain.model.RetentionPolicy
import com.app.domain.repository.ChannelRepository
import com.app.domain.repository.ContactRepository
import com.app.domain.model.GeoPerson
import com.app.domain.repository.ConversationRepository
import com.app.domain.repository.GeohashRepository
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.JoinResult
import com.app.domain.repository.MessageRepository
import com.app.domain.repository.MessageTransport
import com.app.domain.repository.PeerRepository
import com.app.domain.usecase.ResolveReachabilityUseCase
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class ChatStoreFactoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val conversationId = ConversationId.Channel("dev")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeMessageRepository(
        val flow: MutableStateFlow<List<BitMessage>> = MutableStateFlow(emptyList()),
    ) : MessageRepository {
        val appended = mutableListOf<BitMessage>()
        override fun observeMessages(id: ConversationId): Flow<List<BitMessage>> = flow
        override suspend fun snapshot(id: ConversationId): List<BitMessage> = flow.value
        override suspend fun append(id: ConversationId, message: BitMessage) {
            appended += message
            flow.value = flow.value + message
        }
        override suspend fun updateDeliveryStatus(messageId: String, status: DeliveryStatus) = Unit
        override suspend fun remove(messageId: String) = Unit
        override suspend fun clear(id: ConversationId) = Unit
        override suspend fun clearAll() = Unit
    }

    private class RecordingTransport : MessageTransport {
        val publicSends = mutableListOf<Pair<String, String?>>()
        override suspend fun sendPublic(content: String, mentions: List<String>, channel: String?) {
            publicSends += content to channel
        }
        override suspend fun sendPrivate(content: String, to: PeerId, recipientNickname: String?, messageId: String) = Unit
        override suspend fun sendGeohash(content: String, channel: GeohashChannel, nickname: String?) = Unit
        override suspend fun sendAttachment(attachment: Attachment, target: ConversationId, messageId: String) = Unit
        override suspend fun cancelTransfer(messageId: String): Boolean = false
        override suspend fun sendReadReceipt(messageId: String, to: PeerId) = Unit
        override suspend fun sendFavoriteNotification(to: PeerId, isFavorite: Boolean) = Unit
        override suspend fun announceSelf() = Unit
    }

    private class FakeConversationRepository(
        val conversations: MutableStateFlow<List<Conversation>> = MutableStateFlow(emptyList()),
    ) : ConversationRepository {
        val readIds = mutableListOf<ConversationId>()
        override fun observeConversations(): Flow<List<Conversation>> = conversations
        override fun observeUnreadCount(): Flow<Int> = MutableStateFlow(0)
        override suspend fun markRead(id: ConversationId) { readIds += id }
    }

    private class FakeIdentityRepository : IdentityRepository {
        override suspend fun myIdentity(): MyIdentity = MyIdentity(
            peerId = PeerId("abc123"),
            fingerprint = Fingerprint("ff"),
            nickname = "me",
        )
        override fun observeVerifiedFingerprints(): Flow<Set<Fingerprint>> = MutableStateFlow(emptySet())
        override suspend fun isVerified(fingerprint: Fingerprint): Boolean = false
        override suspend fun setVerified(fingerprint: Fingerprint, verified: Boolean) = Unit
        override suspend fun panicWipe() = Unit
    }

    private class FakePeerRepository(private val peers: List<Peer> = emptyList()) : PeerRepository {
        override fun observePeers(): Flow<List<Peer>> = MutableStateFlow(peers)
        override fun observeConnectionState(): Flow<Boolean> = MutableStateFlow(peers.any { it.isConnected })
        override suspend fun snapshot(): List<Peer> = peers
        override suspend fun peer(id: PeerId): Peer? = peers.firstOrNull { it.id == id }
    }

    private class FakeContactRepository(private val verified: Boolean = false) : ContactRepository {
        override fun observeFavorites(): Flow<Set<Fingerprint>> = MutableStateFlow(emptySet())
        override fun observeContacts(): Flow<List<Contact>> = MutableStateFlow(emptyList())
        override fun observeVerified(noiseKeyHex: String): Flow<Boolean> = MutableStateFlow(verified)
        override suspend fun toggleFavorite(peer: PeerId) = Unit
        override suspend fun isFavorite(peer: PeerId): Boolean = false
        override suspend fun setBlocked(peer: PeerId, blocked: Boolean) = Unit
        override suspend fun isBlocked(peer: PeerId): Boolean = false
        override suspend fun contact(identity: PeerIdentity): Contact? = null
        override suspend fun noiseKeyHexForNostrAlias(alias: PeerId): String? = null
        override suspend fun clearAll() = Unit
    }

    private class FakeChannelRepository : ChannelRepository {
        override fun observeJoinedChannels(): Flow<Set<String>> = MutableStateFlow(emptySet())
        override fun observeChannels(): Flow<List<Channel>> = MutableStateFlow(emptyList())
        override suspend fun join(tag: String, password: String?): JoinResult = JoinResult.Joined
        override suspend fun leave(tag: String) = Unit
        override suspend fun setPassword(tag: String, password: String) = Unit
        override suspend fun isCreator(tag: String): Boolean = false
        override fun observeRetention(tag: String): Flow<RetentionPolicy> =
            MutableStateFlow(RetentionPolicy.KEEP_ALL)
        override suspend fun setRetention(tag: String, policy: RetentionPolicy) = Unit
    }

    private class FakeGeohashRepository(
        private val counts: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap()),
    ) : GeohashRepository {
        val selected = mutableListOf<ConversationId>()
        override fun observeParticipants(): Flow<List<GeoPerson>> = MutableStateFlow(emptyList())
        override fun observeSelectedChannel(): Flow<ConversationId> = MutableStateFlow(ConversationId.PublicMesh)
        override fun observeParticipantCounts(): Flow<Map<String, Int>> = counts
        override suspend fun select(channel: ConversationId) { selected += channel }
        override suspend fun startDirectMessage(pubkeyHex: String): ConversationId =
            ConversationId.Private(PeerId("nostr_$pubkeyHex"))
        override suspend fun setBlocked(pubkeyHex: String, blocked: Boolean) = Unit
        override suspend fun isTeleported(pubkeyHex: String): Boolean = false
        override suspend fun pubkeyForNickname(nickname: String): String? = null
        override suspend fun pubkeyForShortId(shortId: String): String? = null
    }

    private fun message(id: String, content: String, mine: Boolean = false) = BitMessage(
        id = id,
        conversationId = conversationId,
        sender = SenderRef(peerId = if (mine) PeerId("abc123") else PeerId("peer"), displayName = "x"),
        content = content,
        timestamp = Instant.fromEpochMilliseconds(0),
        isMine = mine,
    )

    private fun factory(
        messages: FakeMessageRepository = FakeMessageRepository(),
        transport: RecordingTransport = RecordingTransport(),
        conversations: FakeConversationRepository = FakeConversationRepository(),
        identity: FakeIdentityRepository = FakeIdentityRepository(),
        peers: List<Peer> = emptyList(),
        id: ConversationId = conversationId,
        contacts: FakeContactRepository = FakeContactRepository(),
        targetMessageId: String? = null,
        geohash: FakeGeohashRepository = FakeGeohashRepository(),
    ) = ChatStoreFactory(
        storeFactory = DefaultStoreFactory(),
        conversationId = id,
        title = "dev",
        targetMessageId = targetMessageId,
        messageRepository = messages,
        identityRepository = identity,
        conversationRepository = conversations,
        resolveReachability = ResolveReachabilityUseCase(FakePeerRepository(peers), FakeContactRepository()),
        channelRepository = FakeChannelRepository(),
        contactRepository = contacts,
        peerRepository = FakePeerRepository(peers),
        messageTransport = transport,
        geohashRepository = geohash,
    )

    @Test
    fun bootstrap_subscribes_and_clears_loading_with_title() = runTest {
        val store = factory().create()

        assertFalse(store.state.isLoading)
        assertEquals("dev", store.state.title)
        assertEquals(conversationId, store.state.conversationId)
    }

    @Test
    fun channel_is_not_encrypted_and_reachability_follows_mesh() = runTest {
        // No connected peers -> OFFLINE; a channel is broadcast, not E2E.
        val offline = factory().create()
        assertEquals(Reachability.OFFLINE, offline.state.reachability)
        assertFalse(offline.state.isEncrypted)

        val peer = Peer(id = PeerId("1111111111111111"), nickname = "n", isConnected = true, isDirect = true)
        val nearby = factory(peers = listOf(peer)).create()
        assertEquals(Reachability.NEARBY, nearby.state.reachability)
    }

    @Test
    fun repository_emissions_populate_the_timeline() = runTest {
        val messages = FakeMessageRepository()
        val store = factory(messages = messages).create()

        messages.flow.value = listOf(message("1", "hi"), message("2", "yo"))

        assertEquals(listOf("hi", "yo"), store.state.messages.map { it.content })
    }

    @Test
    fun title_resolves_from_the_matching_conversation() = runTest {
        val conversations = FakeConversationRepository()
        val store = factory(conversations = conversations).create()

        assertEquals("dev", store.state.title)

        conversations.conversations.value = listOf(
            Conversation(id = ConversationId.Channel("other"), title = "ignored"),
            Conversation(id = conversationId, title = "#dev channel"),
        )

        assertEquals("#dev channel", store.state.title)
    }

    @Test
    fun bootstrap_marks_the_conversation_read() = runTest {
        val conversations = FakeConversationRepository()
        factory(conversations = conversations).create()

        assertEquals(listOf<ConversationId>(conversationId), conversations.readIds)
    }

    @Test
    fun draft_changed_is_reflected_in_state_and_gates_send() = runTest {
        val store = factory().create()

        assertFalse(store.state.canSend)
        store.accept(ChatStore.Intent.DraftChanged("  "))
        assertFalse(store.state.canSend)
        store.accept(ChatStore.Intent.DraftChanged("hello"))
        assertTrue(store.state.canSend)
    }

    @Test
    fun send_clicked_routes_through_transport_and_clears_draft() = runTest {
        val transport = RecordingTransport()
        val messages = FakeMessageRepository()
        val store = factory(messages = messages, transport = transport).create()

        store.accept(ChatStore.Intent.DraftChanged("hello"))
        store.accept(ChatStore.Intent.SendClicked)

        assertEquals("", store.state.draft)
        assertEquals(listOf<Pair<String, String?>>("hello" to "dev"), transport.publicSends)
        // Local echo appended for the channel timeline.
        assertEquals(listOf("hello"), messages.appended.map { it.content })
    }

    @Test
    fun send_clicked_with_blank_draft_is_ignored() = runTest {
        val transport = RecordingTransport()
        val store = factory(transport = transport).create()

        store.accept(ChatStore.Intent.SendClicked)

        assertTrue(transport.publicSends.isEmpty())
    }

    @Test
    fun private_chat_with_a_verified_peer_reflects_verified() = runTest {
        val store = factory(
            id = ConversationId.Private(PeerId("a".repeat(64))),
            contacts = FakeContactRepository(verified = true),
        ).create()

        assertTrue(store.state.isVerified)
    }

    @Test
    fun channel_chat_is_never_verified() = runTest {
        val store = factory(contacts = FakeContactRepository(verified = true)).create()
        assertFalse(store.state.isVerified)
    }

    @Test
    fun target_message_id_is_carried_into_state() = runTest {
        val store = factory(targetMessageId = "m1").create()
        assertEquals("m1", store.state.targetMessageId)
    }

    @Test
    fun opening_a_geohash_conversation_selects_it_to_start_the_subscription() = runTest {
        val geohash = FakeGeohashRepository()
        val geoId = ConversationId.Geohash(GeohashChannel(GeohashLevel.CITY, "u4pru"))
        factory(id = geoId, geohash = geohash).create()

        assertEquals(listOf<ConversationId>(geoId), geohash.selected)
    }

    @Test
    fun opening_a_non_geohash_conversation_does_not_select_a_location_channel() = runTest {
        val geohash = FakeGeohashRepository()
        factory(geohash = geohash).create()

        assertTrue(geohash.selected.isEmpty())
    }

    @Test
    fun geohash_participant_count_flows_into_state() = runTest {
        val counts = MutableStateFlow(mapOf("u4pru" to 3))
        val geohash = FakeGeohashRepository(counts)
        val geoId = ConversationId.Geohash(GeohashChannel(GeohashLevel.CITY, "u4pru"))
        val store = factory(id = geoId, geohash = geohash).create()

        assertEquals(3, store.state.participantCount)

        counts.value = mapOf("u4pru" to 5)
        assertEquals(5, store.state.participantCount)
    }
}
