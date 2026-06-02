package com.app.domain.repository

import com.app.domain.model.Conversation
import com.app.domain.model.ConversationId
import kotlinx.coroutines.flow.Flow

/**
 * Chat-list aggregate (a messenger trait) and unread tracking.
 */
interface ConversationRepository {

    /** Stream of conversations (for the chat-list screen). */
    fun observeConversations(): Flow<List<Conversation>>

    /** Total unread count (for the badge). */
    fun observeUnreadCount(): Flow<Int>

    /** Mark a conversation as read (reset its local unread counter). */
    suspend fun markRead(id: ConversationId)
}
