package com.yet.bitmessage.feature.chats.details.integration

import com.yet.bitmessage.feature.chats.details.ChatComponent
import com.yet.bitmessage.feature.chats.details.store.ChatStore

internal val stateToModel: (ChatStore.State) -> ChatComponent.Model = { state ->
    ChatComponent.Model(
        conversationId = state.conversationId,
        title = state.title,
        isLoading = state.isLoading,
        messages = state.messages,
        draft = state.draft,
        canSend = state.canSend,
        reachability = state.reachability,
        isEncrypted = state.isEncrypted,
        isVerified = state.isVerified,
        isVouched = state.isVouched,
        voucherCount = state.voucherCount,
        participantCount = state.participantCount,
        isBookmarked = state.isBookmarked,
        participants = state.participants,
        mentionSuggestions = state.mentionSuggestions,
        targetMessageId = state.targetMessageId,
    )
}
