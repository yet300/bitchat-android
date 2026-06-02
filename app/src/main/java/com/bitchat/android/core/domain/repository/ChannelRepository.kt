package com.bitchat.android.core.domain.repository

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

    suspend fun join(tag: String, password: String?): JoinResult

    suspend fun leave(tag: String)

    suspend fun setPassword(tag: String, password: String)

    suspend fun isCreator(tag: String): Boolean
}
