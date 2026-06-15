package com.yet.bitmessage.feature.chats.conversations.store

import com.app.domain.model.Conversation
import com.app.domain.model.ConversationId
import com.app.domain.model.Peer
import com.app.domain.model.Reachability
import com.arkivanov.mvikotlin.core.store.Store

internal interface ConversationsStore :
    Store<ConversationsStore.Intent, ConversationsStore.State, ConversationsStore.Label> {

    data class State(
        val isLoading: Boolean = true,
        val conversations: List<Conversation> = emptyList(),
        val reachability: Map<ConversationId, Reachability> = emptyMap(),
        val nearby: List<Peer> = emptyList(),
        val pinned: Set<ConversationId> = emptySet(),
        val muted: Set<ConversationId> = emptySet(),
        val query: String = "",
    ) {
        /** Client-side chat-list filter on title + last-message text. */
        val visible: List<Conversation>
            get() = if (query.isBlank()) {
                conversations
            } else {
                conversations.filter { conversation ->
                    conversation.title.contains(query, ignoreCase = true) ||
                        conversation.lastMessage?.content?.contains(query, ignoreCase = true) == true
                }
            }
    }

    /** Selection is navigation, owned by the component. */
    sealed interface Intent {
        data class QueryChanged(val text: String) : Intent
        data class TogglePin(val id: ConversationId) : Intent
        data class ToggleMute(val id: ConversationId) : Intent
    }

    sealed interface Action {
        data object Subscribe : Action
    }

    sealed interface Msg {
        data class Loaded(val conversations: List<Conversation>) : Msg
        data class ReachabilityLoaded(val reachability: Map<ConversationId, Reachability>) : Msg
        data class NearbyLoaded(val nearby: List<Peer>) : Msg
        data class PinnedLoaded(val pinned: Set<ConversationId>) : Msg
        data class MutedLoaded(val muted: Set<ConversationId>) : Msg
        data class QueryChanged(val text: String) : Msg
    }

    sealed interface Label
}
