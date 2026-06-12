package com.yet.bitmessage.feature.chats.conversations.store

import com.app.domain.model.Conversation
import com.app.domain.model.ConversationId
import com.app.domain.repository.ConversationRepository
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

    private fun conversation(title: String) = Conversation(
        id = ConversationId.Channel(title),
        title = title,
    )

    @Test
    fun bootstrap_subscribes_and_clears_loading_on_first_emission() = runTest {
        val repository = FakeConversationRepository()
        val store = ConversationsStoreFactory(DefaultStoreFactory(), repository).create()

        assertFalse(store.state.isLoading)
        assertEquals(emptyList(), store.state.conversations)
    }

    @Test
    fun repository_updates_propagate_to_state() = runTest {
        val repository = FakeConversationRepository()
        val store = ConversationsStoreFactory(DefaultStoreFactory(), repository).create()

        repository.conversationsFlow.value = listOf(conversation("alpha"), conversation("beta"))

        assertEquals(listOf("alpha", "beta"), store.state.conversations.map { it.title })

        repository.conversationsFlow.value = listOf(conversation("beta"))
        assertEquals(listOf("beta"), store.state.conversations.map { it.title })
    }

    @Test
    fun initial_state_is_loading_before_bootstrap_runs() {
        // Constructed directly (no auto-init path executed yet on this thread): the
        // declared initial state must mark loading so the UI shows a spinner first.
        assertTrue(ConversationsStore.State().isLoading)
    }
}
