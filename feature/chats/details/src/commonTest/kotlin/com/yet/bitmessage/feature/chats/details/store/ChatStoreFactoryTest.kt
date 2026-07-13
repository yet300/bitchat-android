@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package com.yet.bitmessage.feature.chats.details.store

import com.app.domain.model.Attachment
import com.app.domain.model.AttachmentKind
import com.app.domain.model.BitMessage
import com.app.domain.model.Conversation
import com.app.domain.model.ConversationId
import com.app.domain.model.Fingerprint
import com.app.domain.model.GeohashChannel
import com.app.domain.model.GeohashLevel
import com.app.domain.model.MyIdentity
import com.app.domain.model.Peer
import com.app.domain.model.PeerId
import com.app.domain.model.PeerIdentity
import com.app.domain.model.Reachability
import com.app.domain.model.SenderRef
import com.app.domain.model.Contact
import com.app.domain.repository.ContactRepository
import com.app.domain.model.GeoPerson
import com.app.domain.repository.ConversationRepository
import com.app.domain.repository.GeohashBookmarksRepository
import com.app.domain.repository.VouchRepository
import com.app.domain.repository.GeohashRepository
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.MessageRepository
import com.app.domain.repository.MessageTransport
import com.app.domain.repository.NoiseSessionPort
import com.app.domain.repository.PeerRepository
import com.app.domain.usecase.ResolveReachabilityUseCase
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
        val removed = mutableListOf<String>()
        override suspend fun remove(messageId: String) { removed += messageId }
        override suspend fun clear(id: ConversationId) = Unit
        override suspend fun clearAll() = Unit
    }

    private class RecordingTransport : MessageTransport {
        val publicSends = mutableListOf<Pair<String, String?>>()
        val attachments = mutableListOf<Pair<Attachment, ConversationId>>()
        override suspend fun sendPublic(content: String, mentions: List<String>, channel: String?) {
            publicSends += content to channel
        }
        override suspend fun sendPrivate(content: String, to: PeerId, recipientNickname: String?, messageId: String) = Unit
        override suspend fun sendGeohash(content: String, channel: GeohashChannel, nickname: String?) = Unit
        override suspend fun sendAttachment(attachment: Attachment, target: ConversationId, messageId: String) {
            attachments += attachment to target
        }
        val cancelled = mutableListOf<String>()
        override suspend fun cancelTransfer(messageId: String): Boolean {
            cancelled += messageId
            return true
        }
        override suspend fun sendReadReceipt(messageId: String, to: PeerId) = Unit
        override suspend fun sendFavoriteNotification(to: PeerId, isFavorite: Boolean) = Unit
        override suspend fun announceSelf() = Unit
    }

    private class NoopNoiseSessionPort : NoiseSessionPort {
        override fun hasSession(peerId: PeerId): Boolean = true
        override fun myPeerId(): String = "0000000000000000"
        override fun initiateHandshake(peerId: PeerId) = Unit
        override fun announceTo(peerId: PeerId) = Unit
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
        override fun isBlocked(peer: PeerId): Boolean = false
        override suspend fun contact(identity: PeerIdentity): Contact? = null
        override suspend fun noiseKeyHexForNostrAlias(alias: PeerId): String? = null
        override suspend fun clearAll() = Unit
    }


    private class FakeGeohashBookmarks : GeohashBookmarksRepository {
        private val set = MutableStateFlow<Set<String>>(emptySet())
        val toggled = mutableListOf<String>()
        override fun observeBookmarks(): Flow<List<String>> = set.map { it.toMutableList() }
        override fun observeIsBookmarked(geohash: String): Flow<Boolean> = set.map { geohash in it }
        override suspend fun toggle(geohash: String) {
            toggled += geohash
            set.value = if (geohash in set.value) set.value - geohash else set.value + geohash
        }
    }

    private class FakeVouchRepository(
        private val vouchedFingerprints: Set<String> = emptySet(),
        private val vouchers: List<Fingerprint> = emptyList(),
    ) : VouchRepository {
        override suspend fun isVouched(fingerprint: Fingerprint): Boolean =
            fingerprint.value in vouchedFingerprints
        override suspend fun validVouchers(fingerprint: Fingerprint): List<Fingerprint> =
            if (fingerprint.value in vouchedFingerprints) vouchers else emptyList()
    }

    private class FakeGeohashRepository(
        private val counts: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap()),
        private val people: MutableStateFlow<List<GeoPerson>> = MutableStateFlow(emptyList()),
    ) : GeohashRepository {
        val selected = mutableListOf<ConversationId>()
        override fun observeParticipants(): Flow<List<GeoPerson>> = people
        override fun observeSelectedChannel(): Flow<ConversationId> = MutableStateFlow(ConversationId.PublicMesh)
        override fun observeParticipantCounts(): Flow<Map<String, Int>> = counts
        override suspend fun select(channel: ConversationId) { selected += channel }
        override suspend fun startDirectMessage(pubkeyHex: String): ConversationId =
            ConversationId.Private(PeerId("nostr_$pubkeyHex"))
        override suspend fun setBlocked(pubkeyHex: String, blocked: Boolean) = Unit
        override suspend fun isUserBlocked(pubkeyHex: String): Boolean = false
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
        bookmarks: FakeGeohashBookmarks = FakeGeohashBookmarks(),
        vouch: FakeVouchRepository = FakeVouchRepository(),
        noiseSession: NoiseSessionPort = NoopNoiseSessionPort(),
    ) = ChatStoreFactory(
        storeFactory = DefaultStoreFactory(),
        conversationId = id,
        title = "dev",
        targetMessageId = targetMessageId,
        messageRepository = messages,
        identityRepository = identity,
        conversationRepository = conversations,
        resolveReachability = ResolveReachabilityUseCase(FakePeerRepository(peers), FakeContactRepository()),
        contactRepository = contacts,
        peerRepository = FakePeerRepository(peers),
        messageTransport = transport,
        geohashRepository = geohash,
        geohashBookmarks = bookmarks,
        vouchRepository = vouch,
        noiseSession = noiseSession,
    )

    @Test
    fun bootstrap_subscribes_and_clears_loading_with_title() = runTest {
        val store = factory().create()

        assertFalse(store.state.isLoading)
        assertEquals("dev", store.state.title)
        assertEquals(conversationId, store.state.conversationId)
    }

    @Test
    fun vouched_tier_is_surfaced_for_a_dm_peer_a_verified_contact_vouches_for() = runTest {
        val noiseKey = "a".repeat(64)
        val peer = Peer(
            id = PeerId(noiseKey), nickname = "n", isConnected = true, isDirect = true,
            fingerprint = Fingerprint("fp-1"),
        )
        val store = factory(
            id = ConversationId.Private(PeerId(noiseKey)),
            peers = listOf(peer),
            vouch = FakeVouchRepository(setOf("fp-1"), listOf(Fingerprint("voucher-1"))),
        ).create()

        assertTrue(store.state.isVouched)
        assertEquals(1, store.state.voucherCount)
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
    fun send_parses_known_at_mentions_into_the_echo() = runTest {
        val messages = FakeMessageRepository()
        val bob = Peer(id = PeerId("2222222222222222"), nickname = "bob", isConnected = true, isDirect = true)
        val store = factory(messages = messages, peers = listOf(bob)).create()

        store.accept(ChatStore.Intent.DraftChanged("hi @bob and @carol"))
        store.accept(ChatStore.Intent.SendClicked)

        // Only the known nickname (bob) is a mention; carol is not a peer.
        assertEquals(listOf("bob"), messages.appended.single().mentions)
    }

    @Test
    fun typing_an_at_prefix_suggests_matching_peers_and_selection_completes_the_token() = runTest {
        val bob = Peer(id = PeerId("2222222222222222"), nickname = "bob", isConnected = true, isDirect = true)
        val bea = Peer(id = PeerId("3333333333333333"), nickname = "bea", isConnected = true, isDirect = true)
        val store = factory(peers = listOf(bob, bea)).create()

        store.accept(ChatStore.Intent.DraftChanged("hey @b"))
        assertEquals(listOf("bea", "bob"), store.state.mentionSuggestions)

        store.accept(ChatStore.Intent.MentionSelected("bob"))
        assertEquals("hey @bob ", store.state.draft)
        // Token is complete -> no further suggestions.
        assertTrue(store.state.mentionSuggestions.isEmpty())
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
    fun toggling_a_geohash_bookmark_persists_and_reflects_in_state() = runTest {
        val bookmarks = FakeGeohashBookmarks()
        val geoId = ConversationId.Geohash(GeohashChannel(GeohashLevel.CITY, "u4pru"))
        val store = factory(id = geoId, bookmarks = bookmarks).create()

        assertFalse(store.state.isBookmarked)
        store.accept(ChatStore.Intent.ToggleBookmark)
        assertEquals(listOf("u4pru"), bookmarks.toggled)
        assertTrue(store.state.isBookmarked)
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

    @Test
    fun geohash_participants_flow_into_state() = runTest {
        val people = MutableStateFlow(listOf(GeoPerson(pubkeyHex = "ab", displayName = "bob")))
        val geohash = FakeGeohashRepository(people = people)
        val geoId = ConversationId.Geohash(GeohashChannel(GeohashLevel.CITY, "u4pru"))
        val store = factory(id = geoId, geohash = geohash).create()

        assertEquals(listOf("bob"), store.state.participants.map { it.displayName })

        people.value = emptyList()
        assertTrue(store.state.participants.isEmpty())
    }

    @Test
    fun sending_an_attachment_echoes_locally_and_routes_through_the_transport() = runTest {
        val transport = RecordingTransport()
        val messages = FakeMessageRepository()
        val store = factory(messages = messages, transport = transport).create()

        val attachment = Attachment(kind = AttachmentKind.IMAGE, ref = "/cache/pic.jpg", mime = "image/jpeg")
        store.accept(ChatStore.Intent.SendAttachment(attachment))

        assertEquals(listOf(attachment), transport.attachments.map { it.first })
        assertEquals(conversationId, transport.attachments.single().second)
        assertEquals(listOf("/cache/pic.jpg"), messages.appended.map { it.content })
    }

    @Test
    fun cancelling_a_transfer_routes_to_the_transport_and_removes_the_message() = runTest {
        val transport = RecordingTransport()
        val messages = FakeMessageRepository()
        val store = factory(messages = messages, transport = transport).create()

        store.accept(ChatStore.Intent.CancelTransfer("MID-9"))

        assertEquals(listOf("MID-9"), transport.cancelled)
        assertEquals(listOf("MID-9"), messages.removed)
    }

    @Test
    fun clicking_a_participant_publishes_open_conversation_for_the_geohash_dm() = runTest {
        val geohash = FakeGeohashRepository()
        val geoId = ConversationId.Geohash(GeohashChannel(GeohashLevel.CITY, "u4pru"))
        val store = factory(id = geoId, geohash = geohash).create()
        val labels = mutableListOf<ChatStore.Label>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.labels.toList(labels) }

        store.accept(ChatStore.Intent.ParticipantClicked("abcd"))

        val opened = labels.filterIsInstance<ChatStore.Label.OpenConversation>().single()
        assertEquals(ConversationId.Private(PeerId("nostr_abcd")), opened.id)
    }
}
