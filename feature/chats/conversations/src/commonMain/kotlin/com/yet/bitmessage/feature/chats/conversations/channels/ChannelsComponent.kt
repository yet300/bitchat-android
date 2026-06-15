package com.yet.bitmessage.feature.chats.conversations.channels

import com.app.domain.model.Channel
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value

/**
 * Channel management (D6): the joined channels, with join (password-aware), leave and
 * set/change-password actions. Tapping a channel opens its chat (navigation owned by the parent).
 * Per-channel retention is a follow-up slice.
 */
interface ChannelsComponent {

    val model: Value<Model>

    /** Open the channel's conversation. */
    fun onChannelClicked(tag: String)

    /** Join a channel by name (prompts for a password if the channel is protected). */
    fun onJoin(tag: String)

    /** Re-submit a join with the password the user supplied to the prompt. */
    fun onSubmitPassword(tag: String, password: String)

    fun onDismissPasswordPrompt()

    fun onLeave(tag: String)

    fun onSetPassword(tag: String, password: String)

    fun onCloseClicked()

    data class Model(
        val isLoading: Boolean,
        val channels: List<Channel>,
        val passwordPromptFor: String?,
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
