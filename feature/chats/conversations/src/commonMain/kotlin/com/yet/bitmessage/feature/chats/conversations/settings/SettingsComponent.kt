package com.yet.bitmessage.feature.chats.conversations.settings

import com.app.domain.model.ThemeMode
import com.app.domain.repository.PowDifficultyLevel
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value

/**
 * Settings tree (D5): identity (nickname + npub), appearance (theme), network (Tor, PoW),
 * background (auto-start / run-in-background) and the destructive panic wipe. The notifications
 * section is a follow-up slice.
 */
interface SettingsComponent {

    val model: Value<Model>

    fun onNicknameChanged(text: String)

    fun onThemeSelected(mode: ThemeMode)

    fun onTorToggled(enabled: Boolean)

    fun onPowToggled(enabled: Boolean)

    fun onPowDifficultySelected(difficulty: Int)

    fun onAutoStartToggled(enabled: Boolean)

    fun onBackgroundToggled(enabled: Boolean)

    /** Wipe all data and the cryptographic identity (irreversible; the UI confirms first). */
    fun onPanicWipe()

    fun onCloseClicked()

    data class Model(
        val nickname: String,
        val npub: String?,
        val fingerprint: String,
        val isWiping: Boolean,
        val theme: ThemeMode,
        val torEnabled: Boolean,
        val powEnabled: Boolean,
        val powDifficulty: Int,
        val powLevels: List<PowDifficultyLevel>,
        val autoStartEnabled: Boolean,
        val backgroundEnabled: Boolean,
    )

    fun interface Factory {
        fun create(componentContext: ComponentContext, onClose: () -> Unit): SettingsComponent
    }
}
