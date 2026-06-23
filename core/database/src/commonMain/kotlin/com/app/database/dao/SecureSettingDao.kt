package com.app.database.dao

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.app.common.AppDispatchers
import com.app.database.db.DatabaseManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** Small sensitive scalars kept off plaintext prefs (currently the nickname). */
class SecureSettingDao(
    private val databaseManager: DatabaseManager,
    private val dispatchers: AppDispatchers,
) {

    suspend fun get(key: String): String? = withContext(dispatchers.io) {
        databaseManager.getDb().secureSettingQueries.selectByKey(key).executeAsOneOrNull()
    }

    fun observe(key: String): Flow<String?> = flow {
        val db = databaseManager.getDb()
        emitAll(db.secureSettingQueries.selectByKey(key).asFlow().mapToOneOrNull(dispatchers.io))
    }

    suspend fun put(key: String, value: String) = withContext(dispatchers.io) {
        databaseManager.getDb().secureSettingQueries.upsert(key, value)
    }

    suspend fun delete(key: String) = withContext(dispatchers.io) {
        databaseManager.getDb().secureSettingQueries.deleteByKey(key)
    }

    suspend fun deleteAll() = withContext(dispatchers.io) {
        databaseManager.getDb().secureSettingQueries.deleteAll()
    }
}
