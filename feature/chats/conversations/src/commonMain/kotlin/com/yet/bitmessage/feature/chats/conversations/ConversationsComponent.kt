package com.yet.bitmessage.feature.chats.conversations

import com.app.domain.model.ConversationId
import com.app.domain.model.Peer
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value

/**
 * Chat list (main panel). Emits the selected [ConversationId] upward — the root
 * component owns navigation.
 */
interface ConversationsComponent {

    val model: Value<Model>

    fun onConversationClicked(id: ConversationId)

    /** Open (or start) a DM with an in-range peer from the nearby rail. */
    fun onNearbyClicked(peer: Peer)

    fun onQueryChanged(text: String)

    data class Model(
        val isLoading: Boolean,
        val conversations: List<ConversationRow>,
        val nearby: List<Peer>,
        val query: String,
    )

    fun interface Factory {
        fun create(
            componentContext: ComponentContext,
            onConversationSelected: (ConversationId) -> Unit,
        ): ConversationsComponent
    }
}
