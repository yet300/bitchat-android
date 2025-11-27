package com.bitchat.android.feature.about.store

import com.arkivanov.mvikotlin.core.store.Store
import com.bitchat.android.net.TorManager
import com.bitchat.android.ui.theme.ThemePreference

internal interface AboutStore : Store<AboutStore.Intent, AboutStore.State, AboutStore.Label> {

    data class State(
        val versionName: String = "1.0.0",
        val themePreference: ThemePreference = ThemePreference.System,
        val powEnabled: Boolean = false,
        val powDifficulty: Int = 0,
        val torStatus: TorManager.TorStatus = TorManager.TorStatus(),
        val isLoading: Boolean = false
    )

    sealed class Intent {
        data class SetTheme(val theme: ThemePreference) : Intent()
        data class SetPowEnabled(val enabled: Boolean) : Intent()
        data class SetPowDifficulty(val difficulty: Int) : Intent()
        data class SetTorMode(val enabled: Boolean) : Intent()
    }

    sealed class Action {
        data object Init : Action()
    }

    sealed class Msg {
        data class ThemeChanged(val theme: ThemePreference) : Msg()
        data class PowEnabledChanged(val enabled: Boolean) : Msg()
        data class PowDifficultyChanged(val difficulty: Int) : Msg()
        data class TorStatusChanged(val status: TorManager.TorStatus) : Msg()
    }

    sealed class Label
}
