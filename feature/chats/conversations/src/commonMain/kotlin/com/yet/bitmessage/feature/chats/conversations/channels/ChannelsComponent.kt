package com.yet.bitmessage.feature.chats.conversations.channels

import com.app.domain.model.Channel
import com.app.domain.model.RetentionPolicy
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.value.Value

/**
 * Channel management (D6): the joined channels, with join (password-aware), leave,
 * set/change-password and per-channel message-retention. Dialogs are a Decompose
 * [ChildSlot] ([dialog]); tapping a channel opens its chat (navigation owned by the parent).
 */
interface ChannelsComponent {

    val model: Value<Model>

    /** The active dialog (password / retention), or none. */
    val dialog: Value<ChildSlot<*, ChannelDialog>>

    /** Open the channel's conversation. */
    fun onChannelClicked(tag: String)

    /** Join a channel by name (a protected channel opens the password dialog). */
    fun onJoin(tag: String)

    /** Open the set/change-password dialog for a channel. */
    fun onSetPasswordClicked(tag: String)

    /** Open the retention picker for a channel (loads its current policy). */
    fun onRetentionClicked(tag: String)

    fun onLeave(tag: String)

    /** Submit the password dialog (sets it, or retries the join, per [mode]). */
    fun onSubmitPassword(tag: String, mode: ChannelDialog.Password.Mode, password: String)

    fun onRetentionSelected(tag: String, policy: RetentionPolicy)

    fun onDismissDialog()

    fun onCloseClicked()

    data class Model(
        val isLoading: Boolean,
        val channels: List<Channel>,
        val error: String?,
    )

    fun interface Factory {
        fun create(
            componentContext: ComponentContext,
            onChannelSelected: (String) -> Unit,
            onClose: () -> Unit,
        ): ChannelsComponent
    }
}
