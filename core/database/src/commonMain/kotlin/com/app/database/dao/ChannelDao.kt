package com.app.database.dao

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.app.common.AppDispatchers
import com.app.database.Channel
import com.app.database.db.DatabaseManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** Channel membership / protection / key-commitment / retention. Returns the generated [Channel]. */
class ChannelDao(
    private val databaseManager: DatabaseManager,
    private val dispatchers: AppDispatchers,
) {

    suspend fun all(): List<Channel> = withContext(dispatchers.io) {
        databaseManager.getDb().channelQueries.selectAll().executeAsList()
    }

    fun observeAll(): Flow<List<Channel>> = flow {
        val db = databaseManager.getDb()
        emitAll(db.channelQueries.selectAll().asFlow().mapToList(dispatchers.io))
    }

    suspend fun joined(): List<String> = withContext(dispatchers.io) {
        databaseManager.getDb().channelQueries.selectJoined().executeAsList()
    }

    fun observeJoined(): Flow<List<String>> = flow {
        val db = databaseManager.getDb()
        emitAll(db.channelQueries.selectJoined().asFlow().mapToList(dispatchers.io))
    }

    suspend fun byTag(tag: String): Channel? = withContext(dispatchers.io) {
        databaseManager.getDb().channelQueries.selectByTag(tag).executeAsOneOrNull()
    }

    suspend fun upsert(channel: Channel) = withContext(dispatchers.io) {
        databaseManager.getDb().channelQueries.upsert(
            tag = channel.tag,
            is_joined = channel.is_joined,
            is_password_protected = channel.is_password_protected,
            key_commitment = channel.key_commitment,
            retention_policy = channel.retention_policy,
        )
    }

    suspend fun setJoined(tag: String, isJoined: Boolean) = withContext(dispatchers.io) {
        databaseManager.getDb().channelQueries.setJoined(if (isJoined) 1L else 0L, tag)
    }

    suspend fun setRetention(tag: String, policy: String) = withContext(dispatchers.io) {
        databaseManager.getDb().channelQueries.setRetention(policy, tag)
    }

    suspend fun deleteByTag(tag: String) = withContext(dispatchers.io) {
        databaseManager.getDb().channelQueries.deleteByTag(tag)
    }

    suspend fun deleteAll() = withContext(dispatchers.io) {
        databaseManager.getDb().channelQueries.deleteAll()
    }
}
