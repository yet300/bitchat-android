package com.yet.bitmessage.feature.chats.conversations.channels.store

import com.app.domain.model.Channel
import com.app.domain.model.RetentionPolicy
import com.arkivanov.mvikotlin.core.store.Store

internal interface ChannelsStore :
    Store<ChannelsStore.Intent, ChannelsStore.State, ChannelsStore.Label> {

    data class State(
        val isLoading: Boolean = true,
        val channels: List<Channel> = emptyList(),
        val error: String? = null,
    )

    sealed interface Intent {
        data class Join(val tag: String, val password: String? = null) : Intent
        data class Leave(val tag: String) : Intent
        data class SetPassword(val tag: String, val password: String) : Intent
        /** Load the channel's current retention policy (answered via [Label.ShowRetention]). */
        data class OpenRetention(val tag: String) : Intent
        data class SetRetention(val tag: String, val policy: RetentionPolicy) : Intent
    }

    sealed interface Action {
        data object Subscribe : Action
    }

    sealed interface Msg {
        data class Loaded(val channels: List<Channel>) : Msg
        data class Error(val message: String?) : Msg
    }

    /** One-shot events that drive the dialog ChildSlot in the component. */
    sealed interface Label {
        data class NeedsPassword(val tag: String) : Label
        data class ShowRetention(val tag: String, val current: RetentionPolicy) : Label
    }
}
