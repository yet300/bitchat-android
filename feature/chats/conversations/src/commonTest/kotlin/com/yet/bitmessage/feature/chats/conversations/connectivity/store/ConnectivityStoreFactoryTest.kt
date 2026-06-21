package com.yet.bitmessage.feature.chats.conversations.connectivity.store

import com.app.domain.model.Contact
import com.app.domain.model.Fingerprint
import com.app.domain.model.Peer
import com.app.domain.model.PeerId
import com.app.domain.model.PeerIdentity
import com.app.domain.model.TransportKind
import com.app.domain.model.TransportState
import com.app.domain.model.TransportStatus
import com.app.domain.repository.ConnectivityRepository
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.PeerRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityStoreFactoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeConnectivityRepository(
        private val statuses: List<TransportStatus>,
    ) : ConnectivityRepository {
        val enabled = mutableListOf<TransportKind>()
        override fun observe(): Flow<List<TransportStatus>> = flowOf(statuses)
        override suspend fun enable(kind: TransportKind) { enabled += kind }
    }

    private class FakePeerRepository(private val peers: List<Peer>) : PeerRepository {
        override fun observePeers(): Flow<List<Peer>> = MutableStateFlow(peers)
        override fun observeConnectionState(): Flow<Boolean> = MutableStateFlow(peers.any { it.isConnected })
        override suspend fun snapshot(): List<Peer> = peers
        override suspend fun peer(id: PeerId): Peer? = peers.firstOrNull { it.id == id }
    }

    private class FakeContactRepository(favorites: Set<Fingerprint>) : ContactRepository {
        val favoriteToggles = mutableListOf<PeerId>()
        private val favoritesFlow = MutableStateFlow(favorites)
        override fun observeFavorites(): Flow<Set<Fingerprint>> = favoritesFlow
        override fun observeContacts(): Flow<List<Contact>> = MutableStateFlow(emptyList())
        override fun observeVerified(noiseKeyHex: String): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun toggleFavorite(peer: PeerId) { favoriteToggles += peer }
        override suspend fun isFavorite(peer: PeerId): Boolean = false
        override suspend fun setBlocked(peer: PeerId, blocked: Boolean) = Unit
        override fun isBlocked(peer: PeerId): Boolean = false
        override suspend fun contact(identity: PeerIdentity): Contact? = null
        override suspend fun noiseKeyHexForNostrAlias(alias: PeerId): String? = null
        override suspend fun clearAll() = Unit
    }

    private fun factory(
        connectivity: FakeConnectivityRepository = FakeConnectivityRepository(emptyList()),
        peers: List<Peer> = emptyList(),
        contacts: FakeContactRepository = FakeContactRepository(emptySet()),
    ) = ConnectivityStoreFactory(DefaultStoreFactory(), connectivity, FakePeerRepository(peers), contacts)

    @Test
    fun loads_statuses_and_routes_enable_to_repository() = runTest {
        val repo = FakeConnectivityRepository(
            listOf(
                TransportStatus(TransportKind.BLUETOOTH, TransportState.OFF),
                TransportStatus(TransportKind.INTERNET, TransportState.ON),
            ),
        )
        val store = factory(connectivity = repo).create()

        assertEquals(2, store.state.statuses.size)
        assertEquals(TransportState.OFF, store.state.statuses.first().state)

        store.accept(ConnectivityStore.Intent.Enable(TransportKind.BLUETOOTH))
        assertEquals(listOf(TransportKind.BLUETOOTH), repo.enabled)
    }

    @Test
    fun loads_peers_and_favorites_and_routes_toggle_favorite() = runTest {
        val peer = Peer(id = PeerId("1111111111111111"), nickname = "ann", isConnected = true, isDirect = true)
        val contacts = FakeContactRepository(setOf(Fingerprint("fp1")))
        val store = factory(peers = listOf(peer), contacts = contacts).create()

        assertEquals(listOf("ann"), store.state.peers.map { it.nickname })
        assertTrue(Fingerprint("fp1") in store.state.favorites)

        store.accept(ConnectivityStore.Intent.ToggleFavorite(peer.id))
        assertEquals(listOf(peer.id), contacts.favoriteToggles)
    }
}
