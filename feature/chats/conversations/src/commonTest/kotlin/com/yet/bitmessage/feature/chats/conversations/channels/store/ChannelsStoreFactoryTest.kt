@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yet.bitmessage.feature.chats.conversations.channels.store

import com.app.domain.model.BitMessage
import com.app.domain.model.Channel
import com.app.domain.model.ConversationId
import com.app.domain.model.RetentionPolicy
import com.app.domain.repository.ChannelRepository
import com.app.domain.repository.JoinResult
import com.app.domain.repository.MessageRepository
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertTrue

class ChannelsStoreFactoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(testDispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeChannelRepository(
        initial: List<Channel> = emptyList(),
        private val protectedTags: Set<String> = emptySet(),
    ) : ChannelRepository {
        val channels = MutableStateFlow(initial)
        val left = mutableListOf<String>()
        val passwordsSet = mutableListOf<Pair<String, String>>()
        override fun observeJoinedChannels(): Flow<Set<String>> = MutableStateFlow(emptySet())
        override fun observeChannels(): Flow<List<Channel>> = channels
        override suspend fun join(tag: String, password: String?): JoinResult =
            if (tag in protectedTags && password == null) JoinResult.NeedsPassword else JoinResult.Joined
        override suspend fun leave(tag: String) { left += tag }
        override suspend fun setPassword(tag: String, password: String) { passwordsSet += tag to password }
        override suspend fun isCreator(tag: String): Boolean = false
        val retention = MutableStateFlow(RetentionPolicy.KEEP_ALL)
        val retentionSet = mutableListOf<Pair<String, RetentionPolicy>>()
        override fun observeRetention(tag: String): Flow<RetentionPolicy> = retention
        override suspend fun setRetention(tag: String, policy: RetentionPolicy) {
            retentionSet += tag to policy
            retention.value = policy
        }
    }

    private class FakeMessageRepository : MessageRepository {
        override fun observeMessages(id: ConversationId): Flow<List<BitMessage>> = flowOf(emptyList())
        override suspend fun snapshot(id: ConversationId): List<BitMessage> = emptyList()
        override suspend fun append(id: ConversationId, message: BitMessage) = Unit
        override suspend fun remove(messageId: String) = Unit
        override suspend fun clear(id: ConversationId) = Unit
        override suspend fun clearAll() = Unit
    }

    private fun factory(repo: ChannelRepository, messages: MessageRepository = FakeMessageRepository()) =
        ChannelsStoreFactory(DefaultStoreFactory(), repo, messages)

    @Test
    fun loads_channels() = runTest {
        val repo = FakeChannelRepository(listOf(Channel(tag = "#a", isJoined = true)))
        val store = factory(repo).create()
        assertEquals(listOf("#a"), store.state.channels.map { it.tag })
    }

    @Test
    fun joining_a_protected_channel_without_password_emits_needs_password_label() = runTest {
        val repo = FakeChannelRepository(protectedTags = setOf("#secret"))
        val store = factory(repo).create()
        val labels = mutableListOf<ChannelsStore.Label>()
        val job = launch(testDispatcher) { store.labels.toList(labels) }

        store.accept(ChannelsStore.Intent.Join("#secret"))

        assertEquals(listOf<ChannelsStore.Label>(ChannelsStore.Label.NeedsPassword("#secret")), labels)
        job.cancel()
    }

    @Test
    fun open_retention_emits_current_policy_label_and_set_routes_to_repository() = runTest {
        val repo = FakeChannelRepository(listOf(Channel(tag = "#a", isJoined = true)))
        repo.retention.value = RetentionPolicy.WEEK
        val store = factory(repo).create()
        val labels = mutableListOf<ChannelsStore.Label>()
        val job = launch(testDispatcher) { store.labels.toList(labels) }

        store.accept(ChannelsStore.Intent.OpenRetention("#a"))
        assertEquals(listOf<ChannelsStore.Label>(ChannelsStore.Label.ShowRetention("#a", RetentionPolicy.WEEK)), labels)

        store.accept(ChannelsStore.Intent.SetRetention("#a", RetentionPolicy.DAY))
        assertEquals(listOf("#a" to RetentionPolicy.DAY), repo.retentionSet)
        job.cancel()
    }

    @Test
    fun leave_and_set_password_route_to_repository() = runTest {
        val repo = FakeChannelRepository(listOf(Channel(tag = "#a", isJoined = true)))
        val store = factory(repo).create()

        store.accept(ChannelsStore.Intent.Leave("#a"))
        store.accept(ChannelsStore.Intent.SetPassword("#a", "pw"))

        assertTrue(repo.left.contains("#a"))
        assertEquals(listOf("#a" to "pw"), repo.passwordsSet)
    }
}
