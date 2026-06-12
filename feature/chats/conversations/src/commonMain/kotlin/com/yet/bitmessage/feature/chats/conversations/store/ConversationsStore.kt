package com.yet.bitmessage.feature.chats.conversations.store

import com.app.domain.model.Conversation
import com.arkivanov.mvikotlin.core.store.Store

internal interface ConversationsStore :
    Store<ConversationsStore.Intent, ConversationsStore.State, ConversationsStore.Label> {

    data class State(
        val isLoading: Boolean = true,
        val conversations: List<Conversation> = emptyList(),
    )

    /** Selection is navigation, owned by the component — no intents yet. */
    sealed interface Intent

    sealed interface Action {
        data object Subscribe : Action
    }

    sealed interface Msg {
        data class Loaded(val conversations: List<Conversation>) : Msg
    }

    sealed interface Label
}
