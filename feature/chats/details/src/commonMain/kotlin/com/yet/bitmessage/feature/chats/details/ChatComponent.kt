package com.yet.bitmessage.feature.chats.details

import com.app.domain.model.ConversationId
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value

/**
 * Single conversation screen (details panel). Scaffold stage: exposes only the
 * conversation identity; the message timeline store arrives in the next iteration.
 */
interface ChatComponent {

    val model: Value<Model>

    /** Close the details panel (panels SINGLE mode back navigation). */
    fun onBackClicked()

    data class Model(
        val conversationId: ConversationId,
        val title: String,
    )

    fun interface Factory {
        fun create(
            componentContext: ComponentContext,
            config: ChatConfig,
            onFinished: () -> Unit,
        ): ChatComponent
    }
}
