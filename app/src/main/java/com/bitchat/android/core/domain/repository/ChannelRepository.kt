package com.bitchat.android.core.domain.repository

import kotlinx.coroutines.flow.Flow

/** Результат попытки войти в канал. */
sealed interface JoinResult {
    data object Joined : JoinResult
    data object NeedsPassword : JoinResult
    data class Failed(val reason: String) : JoinResult
}

/**
 * Каналы `#name`. Крипто/деривация ключа канала — в data/crypto, не здесь.
 */
interface ChannelRepository {

    fun observeJoinedChannels(): Flow<Set<String>>

    suspend fun join(tag: String, password: String?): JoinResult

    suspend fun leave(tag: String)

    suspend fun setPassword(tag: String, password: String)

    suspend fun isCreator(tag: String): Boolean
}
