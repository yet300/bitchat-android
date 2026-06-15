@file:OptIn(ExperimentalTime::class)

package com.yet.bitmessage.feature.chats.conversations.search.store

import com.app.domain.model.BitMessage
import com.app.domain.model.Channel
import com.app.domain.model.Contact
import com.app.domain.model.Conversation
import com.app.domain.model.ConversationId
import com.app.domain.model.Peer
import com.app.domain.model.PeerId
import com.app.domain.model.PeerIdentity
import com.app.domain.model.SenderRef
import com.app.domain.repository.ConversationRepository
import com.app.domain.repository.PeerRepository
import com.app.domain.model.GeohashLevel
import com.app.domain.repository.SearchRepository
import com.app.domain.usecase.ParseGeohashUseCase
import com.app.domain.usecase.SearchUseCase
import com.yet.bitmessage.feature.chats.conversations.search.SearchTab
import com.yet.bitmessage.feature.chats.conversations.search.searchStateToModel
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SearchStoreFactoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeConversationRepository(
        val flow: MutableStateFlow<List<Conversation>> = MutableStateFlow(emptyList()),
    ) : ConversationRepository {
        override fun observeConversations(): Flow<List<Conversation>> = flow
        override fun observeUnreadCount(): Flow<Int> = flowOf(0)
        override suspend fun markRead(id: ConversationId) = Unit
    }

    private class FakePeerRepository(private val peers: List<Peer>) : PeerRepository {
        override fun observePeers(): Flow<List<Peer>> = flowOf(peers)
        override fun observeConnectionState(): Flow<Boolean> = flowOf(peers.any { it.isConnected })
        override suspend fun snapshot(): List<Peer> = peers
        override suspend fun peer(id: PeerId): Peer? = peers.firstOrNull { it.id == id }
    }

    private class FakeSearchRepository(
        private val messages: List<BitMessage> = emptyList(),
        private val contacts: List<Contact> = emptyList(),
        private val channels: List<Channel> = emptyList(),
    ) : SearchRepository {
        override suspend fun searchMessages(query: String): List<BitMessage> = messages
        override suspend fun searchContacts(query: String): List<Contact> = contacts
        override suspend fun searchChannels(query: String): List<Channel> = channels
    }

    private fun factory(
        conversations: FakeConversationRepository = FakeConversationRepository(),
        peers: List<Peer> = emptyList(),
        search: SearchRepository = FakeSearchRepository(),
    ) = SearchStoreFactory(
        storeFactory = DefaultStoreFactory(),
        conversationRepository = conversations,
        peerRepository = FakePeerRepository(peers),
        searchUseCase = SearchUseCase(search),
        parseGeohash = ParseGeohashUseCase(),
    )

    private fun conversation(title: String) =
        Conversation(id = ConversationId.Channel(title), title = title)

    @Test
    fun blank_query_keeps_every_tab_empty() = runTest {
        val convo = FakeConversationRepository(MutableStateFlow(listOf(conversation("alpha"))))
        val store = factory(conversations = convo).create()

        assertTrue(searchStateToModel(store.state).chats.isEmpty())
        assertTrue(store.state.results.isEmpty)
    }

    @Test
    fun chats_tab_filters_the_live_conversation_list() = runTest {
        val convo = FakeConversationRepository(MutableStateFlow(listOf(conversation("alpha"), conversation("beta"))))
        val store = factory(conversations = convo).create()

        store.accept(SearchStore.Intent.QueryChanged("alp"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("alpha"), searchStateToModel(store.state).chats.map { it.title })
    }

    @Test
    fun people_tab_merges_online_peers_and_contacts() = runTest {
        val peer = Peer(id = PeerId("1111111111111111"), nickname = "neo", isConnected = true, isDirect = true)
        val contact = Contact(
            identity = PeerIdentity(noiseKeyHex = "a".repeat(64)),
            nickname = "neon",
            isFavorite = true,
            theyFavoritedUs = false,
            favoritedAt = Instant.fromEpochMilliseconds(0),
            lastUpdated = Instant.fromEpochMilliseconds(0),
        )
        val store = factory(peers = listOf(peer), search = FakeSearchRepository(contacts = listOf(contact))).create()

        store.accept(SearchStore.Intent.QueryChanged("neo"))
        testDispatcher.scheduler.advanceUntilIdle()

        val people = searchStateToModel(store.state).people
        assertEquals(listOf("neo", "neon"), people.map { it.name })
        assertEquals(listOf(true, false), people.map { it.isOnline })
    }

    @Test
    fun messages_and_channels_come_from_the_search_use_case() = runTest {
        val message = BitMessage(
            id = "1",
            conversationId = ConversationId.PublicMesh,
            sender = SenderRef.SYSTEM,
            content = "hello",
            timestamp = Instant.fromEpochMilliseconds(0),
        )
        val search = FakeSearchRepository(messages = listOf(message), channels = listOf(Channel(tag = "#kotlin")))
        val store = factory(search = search).create()

        store.accept(SearchStore.Intent.QueryChanged("hel"))
        testDispatcher.scheduler.advanceUntilIdle()

        val model = searchStateToModel(store.state)
        assertEquals(listOf("1"), model.messages.map { it.id })
        assertEquals(listOf("#kotlin"), model.channels.map { it.tag })
    }

    @Test
    fun geo_tab_parses_a_geohash_query_into_a_teleport_channel() = runTest {
        val store = factory().create()

        store.accept(SearchStore.Intent.QueryChanged("u4pruyd"))
        testDispatcher.scheduler.advanceUntilIdle()

        val geo = searchStateToModel(store.state).geo
        assertEquals("u4pruyd", geo?.geohash)
        assertEquals(GeohashLevel.BLOCK, geo?.level)
    }

    @Test
    fun focused_empty_state_exposes_nearby_and_recent() = runTest {
        val peer = Peer(id = PeerId("1111111111111111"), nickname = "neo", isConnected = true, isDirect = true)
        val convo = FakeConversationRepository(MutableStateFlow(listOf(conversation("alpha"))))
        val store = factory(conversations = convo, peers = listOf(peer)).create()

        val model = searchStateToModel(store.state)
        assertEquals(listOf("neo"), model.nearby.map { it.name })
        assertEquals(listOf("alpha"), model.recent.map { it.title })
    }

    @Test
    fun tab_selection_is_reflected_in_state() = runTest {
        val store = factory().create()
        store.accept(SearchStore.Intent.TabSelected(SearchTab.CHANNELS))
        assertEquals(SearchTab.CHANNELS, store.state.tab)
    }
}
