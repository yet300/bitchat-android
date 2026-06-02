package com.app.domain.repository

import com.app.domain.model.BitMessage
import com.app.domain.model.ConversationId
import com.app.domain.model.DeliveryStatus
import kotlinx.coroutines.flow.Flow

/**
 * Storage for the message timeline (source of truth for the UI). Deliberately separated from
 * [MessageTransport] (outgoing send): different clients and reasons to change (ISP/SRP).
 */
interface MessageRepository {

    /** Stream of messages for a single conversation. */
    fun observeMessages(id: ConversationId): Flow<List<BitMessage>>

    /** Current snapshot of a conversation's messages (e.g. to send read receipts). */
    suspend fun snapshot(id: ConversationId): List<BitMessage>

    /** Append a message (local echo or incoming). */
    suspend fun append(id: ConversationId, message: BitMessage)

    /**
     * Update delivery status by message id. Contract: status is never downgraded
     * (see [DeliveryStatusPolicy][com.app.domain.model.DeliveryStatusPolicy]).
     */
    suspend fun updateDeliveryStatus(messageId: String, status: DeliveryStatus)

    /** Remove a message from everywhere. */
    suspend fun remove(messageId: String)

    /** Clear a conversation's timeline. */
    suspend fun clear(id: ConversationId)

    /** Clear all timelines (used by the panic wipe). */
    suspend fun clearAll()
}
