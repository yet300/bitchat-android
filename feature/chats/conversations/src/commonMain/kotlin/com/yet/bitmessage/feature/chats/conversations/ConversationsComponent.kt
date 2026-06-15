package com.yet.bitmessage.feature.chats.conversations

import com.app.domain.model.ConversationId
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value

/**
 * Chat list (main panel). Emits the selected [ConversationId] upward — the root
 * component owns navigation.
 */
interface ConversationsComponent {

    val model: Value<Model>

    fun onConversationClicked(id: ConversationId)

    fun onQueryChanged(text: String)

    data class Model(
        val isLoading: Boolean,
        val conversations: List<ConversationRow>,
        val query: String,
    )

    fun interface Factory {
        fun create(
            componentContext: ComponentContext,
            onConversationSelected: (ConversationId) -> Unit,
        ): ConversationsComponent
    }
}
