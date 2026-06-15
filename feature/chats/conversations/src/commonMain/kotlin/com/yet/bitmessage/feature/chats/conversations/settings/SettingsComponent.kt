package com.yet.bitmessage.feature.chats.conversations.settings

import com.app.domain.model.ThemeMode
import com.app.domain.repository.PowDifficultyLevel
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value

/**
 * Settings tree (D5): identity (nickname + npub), appearance (theme), network (Tor, PoW) and the
 * destructive panic wipe. Mesh-background and notifications sections are a follow-up slice.
 */
interface SettingsComponent {

    val model: Value<Model>

    fun onNicknameChanged(text: String)

    fun onThemeSelected(mode: ThemeMode)

    fun onTorToggled(enabled: Boolean)

    fun onPowToggled(enabled: Boolean)

    fun onPowDifficultySelected(difficulty: Int)

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
    )

    fun interface Factory {
        fun create(componentContext: ComponentContext, onClose: () -> Unit): SettingsComponent
    }
}
