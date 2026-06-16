package com.app.domain.repository

import com.app.domain.model.Channel
import com.app.domain.model.RetentionPolicy
import kotlinx.coroutines.flow.Flow

/** Result of an attempt to join a channel. */
sealed interface JoinResult {
    data object Joined : JoinResult
    data object NeedsPassword : JoinResult
    data class Failed(val reason: String) : JoinResult
}

/**
 * Channels `#name`. Channel crypto / key derivation lives in data/crypto, not here.
 */
interface ChannelRepository {

    fun observeJoinedChannels(): Flow<Set<String>>

    /**
     * Stream of the joined channels as [Channel] metadata (password-protected flag annotated) —
     * the data source for the channel-management screen.
     */
    fun observeChannels(): Flow<List<Channel>>

    suspend fun join(tag: String, password: String?): JoinResult

    suspend fun leave(tag: String)

    suspend fun setPassword(tag: String, password: String)

    suspend fun isCreator(tag: String): Boolean

    /** Per-channel message-retention policy. */
    fun observeRetention(tag: String): Flow<RetentionPolicy>

    suspend fun setRetention(tag: String, policy: RetentionPolicy)
}
