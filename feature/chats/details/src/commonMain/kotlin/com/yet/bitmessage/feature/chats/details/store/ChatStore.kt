package com.yet.bitmessage.feature.chats.details.store

import com.app.domain.model.BitMessage
import com.app.domain.model.ConversationId
import com.app.domain.model.Reachability
import com.arkivanov.mvikotlin.core.store.Store

internal interface ChatStore : Store<ChatStore.Intent, ChatStore.State, ChatStore.Label> {

    data class State(
        val conversationId: ConversationId,
        val title: String,
        val isLoading: Boolean = true,
        val messages: List<BitMessage> = emptyList(),
        val draft: String = "",
        val reachability: Reachability = Reachability.OFFLINE,
    ) {
        val canSend: Boolean get() = draft.isNotBlank()

        /** Private DMs are Noise end-to-end encrypted; public/channel/geo are broadcast. */
        val isEncrypted: Boolean get() = conversationId is ConversationId.Private
    }

    sealed interface Intent {
        data class DraftChanged(val text: String) : Intent
        data object SendClicked : Intent
    }

    sealed interface Action {
        data object Subscribe : Action
    }

    sealed interface Msg {
        data class Loaded(val messages: List<BitMessage>) : Msg
        data class DraftChanged(val text: String) : Msg
        data class TitleResolved(val title: String) : Msg
        data class ReachabilityChanged(val reachability: Reachability) : Msg
    }

    sealed interface Label
}
