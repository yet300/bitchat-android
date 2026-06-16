package com.yet.bitmessage.feature.chats.conversations.settings

import com.app.domain.model.ThemeMode
import com.app.domain.repository.PowDifficultyLevel
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.value.Value

/**
 * Settings tree (D5): identity (nickname + npub), appearance (theme), network (Tor, PoW),
 * background (auto-start / run-in-background) and the destructive panic wipe. The notifications
 * section is a follow-up slice.
 */
interface SettingsComponent {

    val model: Value<Model>

    /** The active dialog (panic confirm), or none. */
    val dialog: Value<ChildSlot<*, SettingsDialog>>

    fun onNicknameChanged(text: String)

    fun onThemeSelected(mode: ThemeMode)

    fun onTorToggled(enabled: Boolean)

    fun onPowToggled(enabled: Boolean)

    fun onPowDifficultySelected(difficulty: Int)

    fun onAutoStartToggled(enabled: Boolean)

    fun onBackgroundToggled(enabled: Boolean)

    /** Open the panic-wipe confirmation dialog. */
    fun onPanicWipeClicked()

    /** Confirm the irreversible wipe (all data + cryptographic identity). */
    fun onConfirmPanicWipe()

    fun onDismissDialog()

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
