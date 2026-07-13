package com.yet.bitmessage.feature.chats.conversations.settings.store

import com.app.domain.model.ThemeMode
import com.app.domain.repository.PowDifficultyLevel
import com.arkivanov.mvikotlin.core.store.Store
import com.yet.bitmessage.feature.chats.conversations.settings.NotifPermissionStatus

internal interface SettingsStore :
    Store<SettingsStore.Intent, SettingsStore.State, SettingsStore.Label> {

    data class State(
        val nickname: String = "",
        val npub: String? = null,
        val fingerprint: String = "",
        val isWiping: Boolean = false,
        val theme: ThemeMode = ThemeMode.SYSTEM,
        val torEnabled: Boolean = false,
        val powEnabled: Boolean = false,
        val powDifficulty: Int = 0,
        val powLevels: List<PowDifficultyLevel> = emptyList(),
        val autoStartEnabled: Boolean = true,
        val backgroundEnabled: Boolean = true,
        val notifPermission: NotifPermissionStatus = NotifPermissionStatus.LOADING,
        val globalMuteEnabled: Boolean = false,
        val gatewayEnabled: Boolean = false,
        val myQr: String? = null,
    )

    sealed interface Intent {
        data class NicknameChanged(val text: String) : Intent
        data object PanicWipe : Intent
        data class ThemeSelected(val mode: ThemeMode) : Intent
        data class TorToggled(val enabled: Boolean) : Intent
        data class PowToggled(val enabled: Boolean) : Intent
        data class PowDifficultySelected(val difficulty: Int) : Intent
        data class AutoStartToggled(val enabled: Boolean) : Intent
        data class BackgroundToggled(val enabled: Boolean) : Intent
        data class GlobalMuteToggled(val enabled: Boolean) : Intent
        data class GatewayToggled(val enabled: Boolean) : Intent
        data object EnableNotificationsClicked : Intent
        data object ShowMyQrClicked : Intent
    }

    sealed interface Action {
        data object Load : Action
    }

    sealed interface Msg {
        data class NicknameLoaded(val nickname: String) : Msg
        data class IdentityLoaded(val npub: String?, val fingerprint: String) : Msg
        data class Wiping(val wiping: Boolean) : Msg
        data class ThemeLoaded(val mode: ThemeMode) : Msg
        data class TorLoaded(val enabled: Boolean) : Msg
        data class PowEnabledLoaded(val enabled: Boolean) : Msg
        data class PowDifficultyLoaded(val difficulty: Int) : Msg
        data class AutoStartLoaded(val enabled: Boolean) : Msg
        data class BackgroundLoaded(val enabled: Boolean) : Msg
        data class NotifPermissionLoaded(val status: NotifPermissionStatus) : Msg
        data class GlobalMuteLoaded(val enabled: Boolean) : Msg
        data class GatewayLoaded(val enabled: Boolean) : Msg
        data class MyQrLoaded(val qr: String?) : Msg
    }

    sealed interface Label
}
