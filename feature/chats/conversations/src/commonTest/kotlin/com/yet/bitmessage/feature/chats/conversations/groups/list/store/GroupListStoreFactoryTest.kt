@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yet.bitmessage.feature.chats.conversations.groups.list.store

import com.app.domain.model.GroupInfo
import com.app.domain.model.GroupMessageEvent
import com.app.domain.model.Peer
import com.app.domain.model.PeerId
import com.app.domain.model.SessionState
import com.app.domain.repository.GroupRepository
import com.app.domain.repository.PeerRepository
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupListStoreFactoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(testDispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeGroupRepository(
        initial: List<GroupInfo> = emptyList(),
    ) : GroupRepository {
        var groups = initial
        val created = mutableListOf<String>()
        val invited = mutableListOf<Pair<String, String>>()
        val left = mutableListOf<String>()
        val sent = mutableListOf<Pair<String, String>>()
        override val incomingMessages: Flow<GroupMessageEvent> = MutableSharedFlow()
        override suspend fun listGroups(): List<GroupInfo> = groups
        override suspend fun createGroup(name: String): String? {
            created += name
            groups = groups + GroupInfo("id-$name", name, epoch = 0, memberCount = 1, isCreator = true)
            return "id-$name"
        }
        override suspend fun invite(groupIdHex: String, peerId: String): Boolean {
            invited += groupIdHex to peerId; return true
        }
        override suspend fun removeMember(groupIdHex: String, memberFingerprintHex: String): Boolean = true
        override suspend fun leave(groupIdHex: String) { left += groupIdHex }
        override suspend fun sendMessage(groupIdHex: String, content: String): Boolean {
            sent += groupIdHex to content; return true
        }
    }

    private class FakePeerRepository(peers: List<Peer>) : PeerRepository {
        private val flow = MutableStateFlow(peers)
        override fun observePeers(): Flow<List<Peer>> = flow
        override fun observeConnectionState(): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun snapshot(): List<Peer> = flow.value
        override suspend fun peer(id: PeerId): Peer? = flow.value.firstOrNull { it.id == id }
    }

    private fun peer(id: String, connected: Boolean) =
        Peer(id = PeerId(id), nickname = id, isConnected = connected, isDirect = connected, session = SessionState.ESTABLISHED)

    private fun factory(
        groups: FakeGroupRepository,
        peers: FakePeerRepository = FakePeerRepository(emptyList()),
    ) = GroupListStoreFactory(DefaultStoreFactory(), groups, peers)

    @Test
    fun loads_groups_on_subscribe() = runTest {
        val repo = FakeGroupRepository(listOf(GroupInfo("id-a", "A", 0, 1, true)))
        val store = factory(repo).create()
        assertEquals(listOf("A"), store.state.groups.map { it.name })
    }

    @Test
    fun create_routes_to_repository_and_reloads() = runTest {
        val repo = FakeGroupRepository()
        val store = factory(repo).create()

        store.accept(GroupListStore.Intent.Create("Team"))

        assertEquals(listOf("Team"), repo.created)
        assertEquals(listOf("Team"), store.state.groups.map { it.name })
    }

    @Test
    fun invite_and_leave_route_to_repository() = runTest {
        val repo = FakeGroupRepository(listOf(GroupInfo("id-a", "A", 0, 1, true)))
        val store = factory(repo).create()

        store.accept(GroupListStore.Intent.Invite("id-a", "peer1"))
        store.accept(GroupListStore.Intent.Leave("id-a"))

        assertEquals(listOf("id-a" to "peer1"), repo.invited)
        assertTrue(repo.left.contains("id-a"))
    }

    @Test
    fun only_connected_peers_are_invitable() = runTest {
        val peers = FakePeerRepository(listOf(peer("on", connected = true), peer("off", connected = false)))
        val store = factory(FakeGroupRepository(), peers).create()

        assertEquals(listOf("on"), store.state.invitablePeers.map { it.id.raw })
    }
}
