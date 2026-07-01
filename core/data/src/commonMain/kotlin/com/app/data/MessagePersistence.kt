package com.app.data

import com.app.transport.model.BitchatMessage
import com.app.transport.model.DeliveryStatus

/**
 * Persistence side-channel for the [AppStateStore] timelines. The in-memory StateFlows stay the live,
 * synchronous source of truth (dedup / block / unread all read them); this mirrors every change into
 * the encrypted DB so the timelines survive process death, and rehydrates them on startup ([load]).
 *
 * The mutators are fire-and-forget (called from the non-suspend mesh sink path); the implementation
 * does the suspend DB work on its own scope. [NoOp] is the default for tests that don't exercise
 * persistence.
 */
interface MessagePersistence {

    fun persist(conversationKey: String, message: BitchatMessage)

    fun updateStatus(messageId: String, status: DeliveryStatus)

    fun delete(messageId: String)

    /** Clear one conversation, or all timelines when [conversationKey] is null. */
    fun clear(conversationKey: String?)

    /** Load up to [perConversationLimit] most-recent messages per conversation (key -> oldest-first). */
    suspend fun load(perConversationLimit: Long): Map<String, List<BitchatMessage>>

    object NoOp : MessagePersistence {
        override fun persist(conversationKey: String, message: BitchatMessage) = Unit
        override fun updateStatus(messageId: String, status: DeliveryStatus) = Unit
        override fun delete(messageId: String) = Unit
        override fun clear(conversationKey: String?) = Unit
        override suspend fun load(perConversationLimit: Long): Map<String, List<BitchatMessage>> = emptyMap()
    }
}
