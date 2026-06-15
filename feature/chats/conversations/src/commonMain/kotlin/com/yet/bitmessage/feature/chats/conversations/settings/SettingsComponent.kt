package com.yet.bitmessage.feature.chats.conversations.settings

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value

/**
 * Settings tree (D5). First slice: identity (nickname + npub) and the destructive panic wipe.
 * Theme / Tor / PoW / notifications / mesh-background sections are follow-up slices that need
 * their own domain ports over the existing platform preference managers.
 */
interface SettingsComponent {

    val model: Value<Model>

    fun onNicknameChanged(text: String)

    /** Wipe all data and the cryptographic identity (irreversible; the UI confirms first). */
    fun onPanicWipe()

    fun onCloseClicked()

    data class Model(
        val nickname: String,
        val npub: String?,
        val fingerprint: String,
        val isWiping: Boolean,
    )

    fun interface Factory {
        fun create(componentContext: ComponentContext, onClose: () -> Unit): SettingsComponent
    }
}
