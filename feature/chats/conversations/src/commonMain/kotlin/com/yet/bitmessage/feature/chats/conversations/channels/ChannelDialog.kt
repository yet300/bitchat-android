package com.yet.bitmessage.feature.chats.conversations.channels

import com.app.domain.model.RetentionPolicy

/**
 * Channel-management dialogs hosted in a [ChildSlot][com.arkivanov.decompose.router.slot.ChildSlot].
 * These are lightweight (no business logic of their own — the store handles the action); they carry
 * only what the dialog renders. A heavier dialog would be modeled as its own component instead.
 */
sealed interface ChannelDialog {

    val tag: String

    /** Collect a password — to set/change one ([Mode.SET]) or to retry a protected join ([Mode.JOIN]). */
    data class Password(override val tag: String, val mode: Mode) : ChannelDialog {
        enum class Mode { SET, JOIN }
    }

    /** Pick the channel's message-retention policy (pre-selected to [current]). */
    data class Retention(override val tag: String, val current: RetentionPolicy) : ChannelDialog
}
