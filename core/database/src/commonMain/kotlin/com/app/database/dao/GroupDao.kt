package com.app.database.dao

import com.app.common.AppDispatchers
import com.app.database.Private_group
import com.app.database.db.DatabaseManager
import kotlinx.coroutines.withContext

/**
 * Persistent store of the private groups this device belongs to (`private_group` table). Thin,
 * transport-free CRUD over raw columns; the data layer ([com.app.data.group.GroupStore]) owns the
 * mapping to/from the wire models, key generation, and epoch rotation. The current-epoch symmetric
 * key rides in the SQLCipher-encrypted DB (our keychain equivalent) and is dropped on panic wipe.
 */
class GroupDao(
    private val databaseManager: DatabaseManager,
    private val dispatchers: AppDispatchers,
) {

    /**
     * Insert a new group or update an existing one in place. An update preserves `created_at` and
     * `creator_fingerprint` (a group's creator never changes); a fresh row is stamped with [nowMs].
     */
    suspend fun upsert(
        groupId: ByteArray,
        name: String,
        epoch: Long,
        creatorFingerprint: ByteArray,
        roster: ByteArray,
        groupKey: ByteArray,
        nowMs: Long,
    ) = withContext(dispatchers.io) {
        val queries = databaseManager.getDb().privateGroupQueries
        queries.transaction {
            val existing = queries.selectById(groupId).executeAsOneOrNull()
            if (existing != null) {
                queries.updateState(name = name, epoch = epoch, roster = roster, groupKey = groupKey, groupId = groupId)
            } else {
                queries.insert(
                    group_id = groupId,
                    name = name,
                    epoch = epoch,
                    creator_fingerprint = creatorFingerprint,
                    roster = roster,
                    group_key = groupKey,
                    created_at = nowMs,
                )
            }
        }
    }

    suspend fun selectAll(): List<Private_group> = withContext(dispatchers.io) {
        databaseManager.getDb().privateGroupQueries.selectAll().executeAsList()
    }

    suspend fun selectById(groupId: ByteArray): Private_group? = withContext(dispatchers.io) {
        databaseManager.getDb().privateGroupQueries.selectById(groupId).executeAsOneOrNull()
    }

    suspend fun deleteById(groupId: ByteArray) = withContext(dispatchers.io) {
        databaseManager.getDb().privateGroupQueries.deleteById(groupId)
    }

    /** Panic wipe: drop all groups (keys included). */
    suspend fun wipe() = withContext(dispatchers.io) {
        databaseManager.getDb().privateGroupQueries.deleteAll()
    }
}
