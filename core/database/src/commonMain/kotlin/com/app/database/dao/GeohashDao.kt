package com.app.database.dao

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.app.common.AppDispatchers
import com.app.database.db.DatabaseManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** Geohash bookmarks (ordered) and blocked geohash-chat participants. */
class GeohashDao(
    private val databaseManager: DatabaseManager,
    private val dispatchers: AppDispatchers,
) {

    // --- bookmarks (ordered newest-first by position) ---

    suspend fun bookmarks(): List<String> = withContext(dispatchers.io) {
        databaseManager.getDb().geohashBookmarkQueries.selectAll().executeAsList()
    }

    fun observeBookmarks(): Flow<List<String>> = flow {
        val db = databaseManager.getDb()
        emitAll(db.geohashBookmarkQueries.selectAll().asFlow().mapToList(dispatchers.io))
    }

    suspend fun upsertBookmark(geohash: String, position: Long) = withContext(dispatchers.io) {
        databaseManager.getDb().geohashBookmarkQueries.upsert(geohash, position)
    }

    suspend fun deleteBookmark(geohash: String) = withContext(dispatchers.io) {
        databaseManager.getDb().geohashBookmarkQueries.deleteByGeohash(geohash)
    }

    suspend fun deleteAllBookmarks() = withContext(dispatchers.io) {
        databaseManager.getDb().geohashBookmarkQueries.deleteAll()
    }

    // --- blocked pubkeys ---

    suspend fun blocked(): List<String> = withContext(dispatchers.io) {
        databaseManager.getDb().geohashBlockedQueries.selectAll().executeAsList()
    }

    suspend fun insertBlocked(pubkeyHex: String) = withContext(dispatchers.io) {
        databaseManager.getDb().geohashBlockedQueries.insert(pubkeyHex)
    }

    suspend fun deleteBlocked(pubkeyHex: String) = withContext(dispatchers.io) {
        databaseManager.getDb().geohashBlockedQueries.deleteByPubkey(pubkeyHex)
    }

    suspend fun deleteAllBlocked() = withContext(dispatchers.io) {
        databaseManager.getDb().geohashBlockedQueries.deleteAll()
    }
}
