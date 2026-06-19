package com.yet.bitmessage.feature.chats.conversations.search

import com.app.domain.model.BitMessage
import com.app.domain.model.Channel
import com.app.domain.model.Conversation
import com.app.domain.model.ConversationId
import com.app.domain.model.GeohashChannel
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value

/**
 * Unified full-screen search (D3). Aggregates across chats, people, messages and channels; a tapped
 * result deep-links into its conversation (navigation is owned by the parent, like list selection).
 */
interface SearchComponent {

    val model: Value<Model>

    fun onQueryChanged(text: String)

    fun onTabSelected(tab: SearchTab)

    /** A result was tapped — open its conversation. */
    fun onResultClicked(id: ConversationId)

    /** A message hit was tapped — open its conversation and scroll to that message. */
    fun onMessageClicked(id: ConversationId, messageId: String)

    fun onCloseClicked()

    data class Model(
        val query: String,
        val tab: SearchTab,
        val isActive: Boolean,
        val chats: List<Conversation>,
        val people: List<PersonResult>,
        val messages: List<BitMessage>,
        val channels: List<Channel>,
        val geo: GeohashChannel?,
        val bookmarks: List<GeohashChannel>,
        // Focused-empty state (no query yet): nearby people + recent conversations.
        val nearby: List<PersonResult>,
        val recent: List<Conversation>,
    )

    /** A person hit (in-range peer or saved contact) flattened to what the row needs. */
    data class PersonResult(
        val name: String,
        val conversationId: ConversationId,
        val isOnline: Boolean,
    )

    fun interface Factory {
        fun create(
            componentContext: ComponentContext,
            onResultSelected: (id: ConversationId, targetMessageId: String?) -> Unit,
            onClose: () -> Unit,
        ): SearchComponent
    }
}
