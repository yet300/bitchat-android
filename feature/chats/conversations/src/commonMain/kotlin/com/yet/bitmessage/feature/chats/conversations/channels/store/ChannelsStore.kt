package com.yet.bitmessage.feature.chats.conversations.channels.store

import com.app.domain.model.Channel
import com.arkivanov.mvikotlin.core.store.Store

internal interface ChannelsStore :
    Store<ChannelsStore.Intent, ChannelsStore.State, ChannelsStore.Label> {

    data class State(
        val isLoading: Boolean = true,
        val channels: List<Channel> = emptyList(),
        // Set when a join attempt hit a password-protected channel; the UI prompts for it.
        val passwordPromptFor: String? = null,
        val error: String? = null,
    )

    sealed interface Intent {
        data class Join(val tag: String, val password: String? = null) : Intent
        data class Leave(val tag: String) : Intent
        data class SetPassword(val tag: String, val password: String) : Intent
        data object DismissPasswordPrompt : Intent
    }

    sealed interface Action {
        data object Subscribe : Action
    }

    sealed interface Msg {
        data class Loaded(val channels: List<Channel>) : Msg
        data class PasswordPrompt(val tag: String) : Msg
        data object PromptCleared : Msg
        data class Error(val message: String?) : Msg
    }

    sealed interface Label
}
