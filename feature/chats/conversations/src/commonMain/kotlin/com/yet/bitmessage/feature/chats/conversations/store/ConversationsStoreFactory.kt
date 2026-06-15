package com.yet.bitmessage.feature.chats.conversations.store

import com.app.domain.repository.ConversationRepository
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.launch

internal class ConversationsStoreFactory(
    private val storeFactory: StoreFactory,
    private val conversationRepository: ConversationRepository,
) {
    fun create(): ConversationsStore =
        object : ConversationsStore,
            Store<ConversationsStore.Intent, ConversationsStore.State, ConversationsStore.Label> by storeFactory.create(
                name = "ConversationsStore",
                initialState = ConversationsStore.State(),
                bootstrapper = SimpleBootstrapper(ConversationsStore.Action.Subscribe),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl,
            ) {}

    private object ReducerImpl : Reducer<ConversationsStore.State, ConversationsStore.Msg> {
        override fun ConversationsStore.State.reduce(msg: ConversationsStore.Msg): ConversationsStore.State =
            when (msg) {
                is ConversationsStore.Msg.Loaded -> copy(isLoading = false, conversations = msg.conversations)
                is ConversationsStore.Msg.QueryChanged -> copy(query = msg.text)
            }
    }

    private inner class ExecutorImpl :
        CoroutineExecutor<ConversationsStore.Intent, ConversationsStore.Action, ConversationsStore.State, ConversationsStore.Msg, ConversationsStore.Label>() {

        override fun executeAction(action: ConversationsStore.Action) {
            when (action) {
                ConversationsStore.Action.Subscribe -> scope.launch {
                    conversationRepository.observeConversations().collect { conversations ->
                        dispatch(ConversationsStore.Msg.Loaded(conversations))
                    }
                }
            }
        }

        override fun executeIntent(intent: ConversationsStore.Intent) {
            when (intent) {
                is ConversationsStore.Intent.QueryChanged ->
                    dispatch(ConversationsStore.Msg.QueryChanged(intent.text))
            }
        }
    }
}
