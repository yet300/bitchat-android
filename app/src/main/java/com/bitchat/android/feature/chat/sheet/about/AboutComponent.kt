package com.bitchat.android.feature.chat.sheet.about

import com.arkivanov.decompose.value.Value
import com.bitchat.android.net.TorManager
import com.bitchat.android.ui.theme.ThemePreference

interface AboutComponent {
    val model: Value<Model>

    fun onThemeChanged(theme: ThemePreference)
    fun onPowEnabledChanged(enabled: Boolean)
    fun onPowDifficultyChanged(difficulty: Int)
    fun onTorModeChanged(enabled: Boolean)
    fun onDismiss()

    data class Model(
        val versionName: String,
        val themePreference: ThemePreference,
        val powEnabled: Boolean,
        val powDifficulty: Int,
        val torStatus: TorManager.TorStatus
    )
}
