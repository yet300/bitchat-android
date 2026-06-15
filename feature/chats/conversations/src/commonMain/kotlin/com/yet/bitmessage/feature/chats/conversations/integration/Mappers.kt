package com.yet.bitmessage.feature.chats.conversations.integration

import com.app.domain.model.Reachability
import com.yet.bitmessage.feature.chats.conversations.ConversationRow
import com.yet.bitmessage.feature.chats.conversations.ConversationsComponent
import com.yet.bitmessage.feature.chats.conversations.store.ConversationsStore

internal val stateToModel: (ConversationsStore.State) -> ConversationsComponent.Model = { state ->
    ConversationsComponent.Model(
        isLoading = state.isLoading,
        // Pinned first (stable sort preserves the repository's last-activity order within groups);
        // each row carries its live reachability + pin/mute flags.
        conversations = state.conversations
            .sortedByDescending { it.id in state.pinned }
            .map { conversation ->
                ConversationRow(
                    conversation = conversation,
                    reachability = state.reachability[conversation.id] ?: Reachability.OFFLINE,
                    isPinned = conversation.id in state.pinned,
                    isMuted = conversation.id in state.muted,
                )
            },
        nearby = state.nearby,
        connectivityBanner = state.transportsNeedingAttention
            .takeIf { it.isNotEmpty() && !state.bannerDismissed }
            ?.let { ConversationsComponent.ConnectivityBanner(it) },
    )
}
