package com.bitchat.android.core.domain.usecase

import com.bitchat.android.core.domain.model.ConversationId
import com.bitchat.android.core.domain.repository.ConversationRepository
import com.bitchat.android.core.domain.repository.MessageRepository
import com.bitchat.android.core.domain.repository.MessageTransport

/**
 * Mark a conversation as read: reset the local unread counter and (for private chats) send read
 * receipts for incoming messages.
 */
class MarkConversationReadUseCase(
    private val conversations: ConversationRepository,
    private val messages: MessageRepository,
    private val transport: MessageTransport,
) {
    suspend operator fun invoke(id: ConversationId) {
        conversations.markRead(id)

        if (id is ConversationId.Private) {
            messages.snapshot(id)
                .filter { !it.isMine }
                .forEach { msg ->
                    val sender = msg.sender.peerId ?: return@forEach
                    transport.sendReadReceipt(msg.id, sender)
                }
        }
    }
}
