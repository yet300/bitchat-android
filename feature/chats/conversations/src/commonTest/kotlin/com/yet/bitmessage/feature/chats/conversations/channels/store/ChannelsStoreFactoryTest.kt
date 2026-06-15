@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yet.bitmessage.feature.chats.conversations.channels.store

import com.app.domain.model.Channel
import com.app.domain.repository.ChannelRepository
import com.app.domain.repository.JoinResult
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
import kotlin.test.assertNull
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
    }

    private fun factory(repo: ChannelRepository) =
        ChannelsStoreFactory(DefaultStoreFactory(), repo)

    @Test
    fun loads_channels() = runTest {
        val repo = FakeChannelRepository(listOf(Channel(tag = "#a", isJoined = true)))
        val store = factory(repo).create()
        assertEquals(listOf("#a"), store.state.channels.map { it.tag })
    }

    @Test
    fun joining_a_protected_channel_without_password_prompts() = runTest {
        val repo = FakeChannelRepository(protectedTags = setOf("#secret"))
        val store = factory(repo).create()

        store.accept(ChannelsStore.Intent.Join("#secret"))
        assertEquals("#secret", store.state.passwordPromptFor)

        store.accept(ChannelsStore.Intent.Join("#secret", password = "pw"))
        assertNull(store.state.passwordPromptFor)
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
