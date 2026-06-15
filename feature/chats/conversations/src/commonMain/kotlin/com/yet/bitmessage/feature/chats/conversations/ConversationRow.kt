package com.yet.bitmessage.feature.chats.conversations

import com.app.domain.model.Conversation
import com.app.domain.model.Reachability

/** A conversation paired with its live [Reachability] and per-conversation flags for the row. */
data class ConversationRow(
    val conversation: Conversation,
    val reachability: Reachability,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
)
