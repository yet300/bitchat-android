@file:OptIn(ExperimentalTime::class)

package com.bitchat.android.core.domain.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Агрегат для экрана списка чатов (атрибут мессенджера). Вид чата выводится из [id]
 * (sealed [ConversationId]), поэтому отдельного «kind» нет.
 */
data class Conversation(
    val id: ConversationId,
    val title: String,
    val lastMessage: BitMessage? = null,
    val unreadCount: Int = 0,
    val lastActivity: Instant? = null,
)
