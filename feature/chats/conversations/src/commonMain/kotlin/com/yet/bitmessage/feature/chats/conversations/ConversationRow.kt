package com.yet.bitmessage.feature.chats.conversations

import com.app.domain.model.Conversation
import com.app.domain.model.Reachability

/** A conversation paired with its live [Reachability] for the list row. */
data class ConversationRow(
    val conversation: Conversation,
    val reachability: Reachability,
)
