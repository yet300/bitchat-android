package com.yet.bitmessage.feature.chats.conversations.settings.store

import com.arkivanov.mvikotlin.core.store.Store

internal interface SettingsStore :
    Store<SettingsStore.Intent, SettingsStore.State, SettingsStore.Label> {

    data class State(
        val nickname: String = "",
        val npub: String? = null,
        val fingerprint: String = "",
        val isWiping: Boolean = false,
    )

    sealed interface Intent {
        data class NicknameChanged(val text: String) : Intent
        data object PanicWipe : Intent
    }

    sealed interface Action {
        data object Load : Action
    }

    sealed interface Msg {
        data class NicknameLoaded(val nickname: String) : Msg
        data class IdentityLoaded(val npub: String?, val fingerprint: String) : Msg
        data class Wiping(val wiping: Boolean) : Msg
    }

    sealed interface Label
}
