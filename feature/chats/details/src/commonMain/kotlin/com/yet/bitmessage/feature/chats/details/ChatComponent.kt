package com.yet.bitmessage.feature.chats.details

import com.app.domain.model.BitMessage
import com.app.domain.model.ConversationId
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value

/**
 * Single conversation screen (details panel): a live message timeline backed by
 * [MessageRepository][com.app.domain.repository.MessageRepository] plus a text input
 * that sends through the domain transport.
 */
interface ChatComponent {

    val model: Value<Model>

    fun onDraftChanged(text: String)

    fun onSendClicked()

    /** Close the details panel (panels SINGLE mode back navigation). */
    fun onBackClicked()

    data class Model(
        val conversationId: ConversationId,
        val title: String,
        val isLoading: Boolean,
        val messages: List<BitMessage>,
        val draft: String,
        val canSend: Boolean,
    )

    fun interface Factory {
        fun create(
            componentContext: ComponentContext,
            config: ChatConfig,
            onFinished: () -> Unit,
        ): ChatComponent
    }
}
