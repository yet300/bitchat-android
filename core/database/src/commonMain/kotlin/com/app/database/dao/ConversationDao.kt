package com.app.database.dao

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.app.common.AppDispatchers
import com.app.database.Conversation
import com.app.database.db.DatabaseManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** Chat-list aggregate metadata (unread / last activity / title). */
class ConversationDao(
    private val databaseManager: DatabaseManager,
    private val dispatchers: AppDispatchers,
) {

    suspend fun all(): List<Conversation> = withContext(dispatchers.io) {
        databaseManager.getDb().conversationQueries.selectAll().executeAsList()
    }

    fun observeAll(): Flow<List<Conversation>> = flow {
        val db = databaseManager.getDb()
        emitAll(db.conversationQueries.selectAll().asFlow().mapToList(dispatchers.io))
    }

    suspend fun upsert(conversation: Conversation) = withContext(dispatchers.io) {
        databaseManager.getDb().conversationQueries.upsert(
            id = conversation.id,
            title = conversation.title,
            last_activity = conversation.last_activity,
            unread_count = conversation.unread_count,
        )
    }

    suspend fun setUnread(id: String, count: Long) = withContext(dispatchers.io) {
        databaseManager.getDb().conversationQueries.setUnread(count, id)
    }

    suspend fun deleteById(id: String) = withContext(dispatchers.io) {
        databaseManager.getDb().conversationQueries.deleteById(id)
    }

    suspend fun deleteAll() = withContext(dispatchers.io) {
        databaseManager.getDb().conversationQueries.deleteAll()
    }
}
