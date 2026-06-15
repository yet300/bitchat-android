package com.yet.bitmessage.feature.chats.conversations.integration

import com.yet.bitmessage.feature.chats.conversations.ConversationsComponent
import com.yet.bitmessage.feature.chats.conversations.store.ConversationsStore

internal val stateToModel: (ConversationsStore.State) -> ConversationsComponent.Model = { state ->
    ConversationsComponent.Model(
        isLoading = state.isLoading,
        conversations = state.visible,
        query = state.query,
    )
}
