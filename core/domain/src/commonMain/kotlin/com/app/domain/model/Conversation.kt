@file:OptIn(ExperimentalTime::class)

package com.app.domain.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Aggregate for the chat-list screen (a messenger trait). The chat kind is derived from [id]
 * (sealed [ConversationId]), so there is no separate "kind" field.
 */
data class Conversation(
    val id: ConversationId,
    val title: String,
    val lastMessage: BitMessage? = null,
    val unreadCount: Int = 0,
    val lastActivity: Instant? = null,
)
