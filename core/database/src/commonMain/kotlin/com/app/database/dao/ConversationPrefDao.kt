package com.app.database.dao

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.app.common.AppDispatchers
import com.app.database.db.DatabaseManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Per-conversation pin/mute flags. `conversationId` is the stable MutePrefsKeys encoding; mapping to
 * [com.app.domain.model.ConversationId] stays in the data layer.
 */
class ConversationPrefDao(
    private val databaseManager: DatabaseManager,
    private val dispatchers: AppDispatchers,
) {

    fun observePinned(): Flow<List<String>> = flow {
        val db = databaseManager.getDb()
        emitAll(db.conversationPrefQueries.selectPinned().asFlow().mapToList(dispatchers.io))
    }

    fun observeMuted(): Flow<List<String>> = flow {
        val db = databaseManager.getDb()
        emitAll(db.conversationPrefQueries.selectMuted().asFlow().mapToList(dispatchers.io))
    }

    suspend fun muted(): List<String> = withContext(dispatchers.io) {
        databaseManager.getDb().conversationPrefQueries.selectMuted().executeAsList()
    }

    suspend fun setPinned(conversationId: String, pinned: Boolean) = withContext(dispatchers.io) {
        databaseManager.getDb().conversationPrefQueries.setPinned(conversationId, if (pinned) 1L else 0L)
    }

    suspend fun setMuted(conversationId: String, muted: Boolean) = withContext(dispatchers.io) {
        databaseManager.getDb().conversationPrefQueries.setMuted(conversationId, if (muted) 1L else 0L)
    }

    suspend fun deleteAll() = withContext(dispatchers.io) {
        databaseManager.getDb().conversationPrefQueries.deleteAll()
    }
}
