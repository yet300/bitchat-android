package com.bitchat.android.feature.chat.sheet.passwordprompt.store

import com.arkivanov.mvikotlin.core.store.Store

interface PasswordPromptStore : Store<PasswordPromptStore.Intent, PasswordPromptStore.State, PasswordPromptStore.Label> {

    sealed interface Intent {
        data class SetPassword(val password: String) : Intent
        data object Confirm : Intent
    }

    data class State(
        val channelName: String,
        val passwordInput: String = "",
        val isError: Boolean = false
    )

    sealed interface Action {
        data object Init : Action
    }

    sealed interface Msg {
        data class PasswordChanged(val password: String) : Msg
        data class ErrorChanged(val isError: Boolean) : Msg
    }

    sealed interface Label {
        data object Dismiss : Label
    }
}
