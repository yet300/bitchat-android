package com.bitchat.android.core.domain.repository

import com.bitchat.android.core.domain.model.Conversation
import com.bitchat.android.core.domain.model.ConversationId
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
