package com.yet.bitmessage.feature.chats.conversations.store

import com.app.domain.model.Contact
import com.app.domain.model.Conversation
import com.app.domain.model.ConversationId
import com.app.domain.model.Peer
import com.app.domain.model.PeerId
import com.app.domain.model.PeerIdentity
import com.app.domain.model.Reachability
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.ConversationPrefsRepository
import com.app.domain.repository.ConversationRepository
import com.app.domain.repository.PeerRepository
import com.app.domain.usecase.ResolveReachabilityUseCase
import com.yet.bitmessage.feature.chats.conversations.integration.stateToModel
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationsStoreFactoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeConversationRepository(
        val conversationsFlow: MutableStateFlow<List<Conversation>> = MutableStateFlow(emptyList()),
    ) : ConversationRepository {
        override fun observeConversations(): Flow<List<Conversation>> = conversationsFlow
        override fun observeUnreadCount(): Flow<Int> = flowOf(0)
        override suspend fun markRead(id: ConversationId) = Unit
    }

    private class FakePeerRepository(private val peers: List<Peer> = emptyList()) : PeerRepository {
        override fun observePeers(): Flow<List<Peer>> = flowOf(peers)
        override fun observeConnectionState(): Flow<Boolean> = flowOf(peers.any { it.isConnected })
        override suspend fun snapshot(): List<Peer> = peers
        override suspend fun peer(id: PeerId): Peer? = peers.firstOrNull { it.id == id }
    }

    private class FakeContactRepository : ContactRepository {
        override fun observeFavorites(): Flow<Set<com.app.domain.model.Fingerprint>> = flowOf(emptySet())
        override suspend fun toggleFavorite(peer: PeerId) = Unit
        override suspend fun isFavorite(peer: PeerId): Boolean = false
        override suspend fun setBlocked(peer: PeerId, blocked: Boolean) = Unit
        override suspend fun isBlocked(peer: PeerId): Boolean = false
        override suspend fun contact(identity: PeerIdentity): Contact? = null
        override suspend fun noiseKeyHexForNostrAlias(alias: PeerId): String? = null
        override suspend fun clearAll() = Unit
    }

    private class FakeConversationPrefsRepository : ConversationPrefsRepository {
        val pinned = MutableStateFlow<Set<ConversationId>>(emptySet())
        val muted = MutableStateFlow<Set<ConversationId>>(emptySet())
        override fun observePinned(): Flow<Set<ConversationId>> = pinned
        override fun observeMuted(): Flow<Set<ConversationId>> = muted
        override suspend fun setPinned(id: ConversationId, pinned: Boolean) {
            this.pinned.value = if (pinned) this.pinned.value + id else this.pinned.value - id
        }
        override suspend fun setMuted(id: ConversationId, muted: Boolean) {
            this.muted.value = if (muted) this.muted.value + id else this.muted.value - id
        }
    }

    private fun storeFactory(
        repository: ConversationRepository,
        peers: List<Peer> = emptyList(),
        prefs: ConversationPrefsRepository = FakeConversationPrefsRepository(),
    ): ConversationsStoreFactory {
        val peerRepository = FakePeerRepository(peers)
        return ConversationsStoreFactory(
            storeFactory = DefaultStoreFactory(),
            conversationRepository = repository,
            peerRepository = peerRepository,
            conversationPrefsRepository = prefs,
            resolveReachability = ResolveReachabilityUseCase(peerRepository, FakeContactRepository()),
        )
    }

    private fun conversation(title: String) = Conversation(
        id = ConversationId.Channel(title),
        title = title,
    )

    @Test
    fun bootstrap_subscribes_and_clears_loading_on_first_emission() = runTest {
        val repository = FakeConversationRepository()
        val store = storeFactory(repository).create()

        assertFalse(store.state.isLoading)
        assertEquals(emptyList(), store.state.conversations)
    }

    @Test
    fun repository_updates_propagate_to_state() = runTest {
        val repository = FakeConversationRepository()
        val store = storeFactory(repository).create()

        repository.conversationsFlow.value = listOf(conversation("alpha"), conversation("beta"))

        assertEquals(listOf("alpha", "beta"), store.state.conversations.map { it.title })

        repository.conversationsFlow.value = listOf(conversation("beta"))
        assertEquals(listOf("beta"), store.state.conversations.map { it.title })
    }

    @Test
    fun reachability_is_resolved_per_conversation() = runTest {
        val repository = FakeConversationRepository()
        // A connected peer makes channels/public mesh NEARBY.
        val peer = Peer(id = PeerId("1111111111111111"), nickname = "n", isConnected = true, isDirect = true)
        val store = storeFactory(repository, peers = listOf(peer)).create()

        repository.conversationsFlow.value = listOf(conversation("alpha"))

        assertEquals(Reachability.NEARBY, store.state.reachability[ConversationId.Channel("alpha")])
    }

    @Test
    fun nearby_lists_connected_peers_direct_first() = runTest {
        val repository = FakeConversationRepository()
        val relayed = Peer(id = PeerId("aaaa"), nickname = "relayed", isConnected = true, isDirect = false)
        val direct = Peer(id = PeerId("bbbb"), nickname = "direct", isConnected = true, isDirect = true)
        val offline = Peer(id = PeerId("cccc"), nickname = "offline", isConnected = false, isDirect = false)
        val store = storeFactory(repository, peers = listOf(relayed, direct, offline)).create()

        assertEquals(listOf("direct", "relayed"), store.state.nearby.map { it.nickname })
    }

    @Test
    fun query_filters_visible_by_title_and_last_message() = runTest {
        val repository = FakeConversationRepository()
        val store = storeFactory(repository).create()

        repository.conversationsFlow.value = listOf(conversation("alpha"), conversation("beta"))

        store.accept(ConversationsStore.Intent.QueryChanged("alp"))
        assertEquals(listOf("alpha"), store.state.visible.map { it.title })
        // Raw list is preserved, only the derived view is filtered.
        assertEquals(listOf("alpha", "beta"), store.state.conversations.map { it.title })

        store.accept(ConversationsStore.Intent.QueryChanged(""))
        assertEquals(listOf("alpha", "beta"), store.state.visible.map { it.title })
    }

    @Test
    fun toggle_pin_persists_and_reflects_in_state() = runTest {
        val repository = FakeConversationRepository()
        val store = storeFactory(repository).create()
        val id = ConversationId.Channel("alpha")

        store.accept(ConversationsStore.Intent.TogglePin(id))
        assertEquals(setOf(id), store.state.pinned)

        store.accept(ConversationsStore.Intent.TogglePin(id))
        assertEquals(emptySet(), store.state.pinned)
    }

    @Test
    fun mapper_sorts_pinned_first_and_flags_rows() {
        val gamma = conversation("gamma")
        val state = ConversationsStore.State(
            isLoading = false,
            conversations = listOf(conversation("alpha"), conversation("beta"), gamma),
            pinned = setOf(gamma.id),
            muted = setOf(gamma.id),
        )

        val rows = stateToModel(state).conversations
        assertEquals(listOf("gamma", "alpha", "beta"), rows.map { it.conversation.title })
        assertTrue(rows.first().isPinned)
        assertTrue(rows.first().isMuted)
    }

    @Test
    fun initial_state_is_loading_before_bootstrap_runs() {
        // Constructed directly (no auto-init path executed yet on this thread): the
        // declared initial state must mark loading so the UI shows a spinner first.
        assertTrue(ConversationsStore.State().isLoading)
    }
}
