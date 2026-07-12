package com.yet.bitmessage.feature.chats.conversations.groups.chat

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value

/**
 * One private group's live conversation (0x25): inbound authenticated messages plus local echoes of
 * what we send. Nothing is persisted — the group stream is ephemeral, so the timeline starts empty
 * each time it is opened.
 */
interface GroupChatComponent {

    val model: Value<Model>

    fun onSend(content: String)

    fun onBackClicked()

    data class Model(
        val title: String,
        val messages: List<Message>,
    )

    /** One rendered line in a group conversation. */
    data class Message(
        val id: String,
        val senderNickname: String,
        val content: String,
        val timestampMs: Long,
        val isMine: Boolean,
    )

    fun interface Factory {
        fun create(
            componentContext: ComponentContext,
            groupIdHex: String,
            title: String,
            onBack: () -> Unit,
        ): GroupChatComponent
    }
}
