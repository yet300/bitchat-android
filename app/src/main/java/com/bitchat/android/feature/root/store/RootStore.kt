package com.bitchat.android.feature.root.store

import com.arkivanov.mvikotlin.core.store.Store
import com.bitchat.android.ui.theme.ThemePreference

interface RootStore : Store<RootStore.Intent, RootStore.State, RootStore.Label> {

    sealed interface Intent {
        data class SetTheme(val theme: ThemePreference) : Intent
    }

    data class State(
        val themePreference: ThemePreference = ThemePreference.System
    )

    sealed interface Action {
        data object Init : Action
    }

    sealed interface Msg {
        data class ThemeChanged(val theme: ThemePreference) : Msg
    }

    sealed interface Label
}
