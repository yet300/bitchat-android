package com.app.domain.repository

import com.app.domain.model.ConversationId
import kotlinx.coroutines.flow.Flow

/**
 * Per-conversation user preferences (pin, mute). Persisted locally; keyed by [ConversationId].
 * Pinning reorders the chat list; muting is a flag the notification layer honors.
 */
interface ConversationPrefsRepository {

    fun observePinned(): Flow<Set<ConversationId>>

    fun observeMuted(): Flow<Set<ConversationId>>

    suspend fun setPinned(id: ConversationId, pinned: Boolean)

    suspend fun setMuted(id: ConversationId, muted: Boolean)
}
