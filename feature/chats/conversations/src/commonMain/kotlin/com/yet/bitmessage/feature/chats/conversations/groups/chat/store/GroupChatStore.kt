package com.yet.bitmessage.feature.chats.conversations.groups.chat.store

import com.arkivanov.mvikotlin.core.store.Store

internal interface GroupChatStore :
    Store<GroupChatStore.Intent, GroupChatStore.State, Nothing> {

    data class State(
        val messages: List<GroupChatMessage> = emptyList(),
    )

    /** One rendered line in a group conversation. Not persisted — the group stream is live. */
    data class GroupChatMessage(
        val id: String,
        val senderNickname: String,
        val content: String,
        val timestampMs: Long,
        val isMine: Boolean,
    )

    sealed interface Intent {
        data class Send(val content: String) : Intent
    }

    sealed interface Action {
        data object Subscribe : Action
    }

    sealed interface Msg {
        data class Appended(val message: GroupChatMessage) : Msg
    }
}
