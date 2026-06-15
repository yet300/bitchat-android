package com.yet.bitmessage.feature.chats.conversations.integration

import com.app.domain.model.Reachability
import com.yet.bitmessage.feature.chats.conversations.ConversationRow
import com.yet.bitmessage.feature.chats.conversations.ConversationsComponent
import com.yet.bitmessage.feature.chats.conversations.store.ConversationsStore

internal val stateToModel: (ConversationsStore.State) -> ConversationsComponent.Model = { state ->
    ConversationsComponent.Model(
        isLoading = state.isLoading,
        // Order comes from the repository (sorted by last activity); pair each with its
        // live reachability, defaulting to OFFLINE until the first reachability emission.
        conversations = state.visible.map { conversation ->
            ConversationRow(conversation, state.reachability[conversation.id] ?: Reachability.OFFLINE)
        },
        query = state.query,
    )
}
