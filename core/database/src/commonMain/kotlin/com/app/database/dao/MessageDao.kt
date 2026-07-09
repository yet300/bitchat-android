package com.app.database.dao

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.app.common.AppDispatchers
import com.app.database.Message
import com.app.database.db.DatabaseManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Synchronous write/read primitives usable *inside* a single [MessageDao.runInTransaction] block.
 * All calls run on the transaction's own connection, so a read observes writes made earlier in the
 * same block — this is what lets a batched insert→status-update pair stay consistent (the status
 * update sees the row its own batch just inserted).
 */
interface MessageTransaction {
    fun upsert(message: Message)
    fun selectById(id: String): Message?
    fun deleteById(id: String)
    fun deleteByConversation(conversationId: String)
    fun deleteAll()
}

/**
 * Message timeline access. Returns the generated [Message] entity; mapping to the domain `BitMessage`
 * stays in the data layer. `conversationId` uses the AppStateStore key scheme
 * (public / private:<peer> / channel:<tag> / geo:<gh>).
 */
class MessageDao(
    private val databaseManager: DatabaseManager,
    private val dispatchers: AppDispatchers,
) {

    /**
     * Run [block] against a single SQLDelight transaction (one fsync for the whole batch instead of
     * one per statement). The generated [MessageQueries] stays hidden behind [MessageTransaction] so
     * callers never depend on generated types.
     */
    suspend fun runInTransaction(block: (MessageTransaction) -> Unit) = withContext(dispatchers.io) {
        val queries = databaseManager.getDb().messageQueries
        queries.transaction {
            block(object : MessageTransaction {
                override fun upsert(message: Message) {
                    queries.upsert(
                        id = message.id,
                        conversation_id = message.conversation_id,
                        sender_peer_id = message.sender_peer_id,
                        sender_name = message.sender_name,
                        content = message.content,
                        timestamp = message.timestamp,
                        type = message.type,
                        is_mine = message.is_mine,
                        is_relay = message.is_relay,
                        mentions = message.mentions,
                        delivery_status = message.delivery_status,
                        attachment_path = message.attachment_path,
                        attachment_type = message.attachment_type,
                        pow_difficulty = message.pow_difficulty,
                        channel = message.channel,
                        wire_json = message.wire_json,
                    )
                }

                override fun selectById(id: String): Message? =
                    queries.selectById(id).executeAsOneOrNull()

                override fun deleteById(id: String) {
                    queries.deleteById(id)
                }

                override fun deleteByConversation(conversationId: String) {
                    queries.deleteByConversation(conversationId)
                }

                override fun deleteAll() {
                    queries.deleteAll()
                }
            })
        }
    }

    suspend fun upsert(message: Message) = withContext(dispatchers.io) {
        databaseManager.getDb().messageQueries.upsert(
            id = message.id,
            conversation_id = message.conversation_id,
            sender_peer_id = message.sender_peer_id,
            sender_name = message.sender_name,
            content = message.content,
            timestamp = message.timestamp,
            type = message.type,
            is_mine = message.is_mine,
            is_relay = message.is_relay,
            mentions = message.mentions,
            delivery_status = message.delivery_status,
            attachment_path = message.attachment_path,
            attachment_type = message.attachment_type,
            pow_difficulty = message.pow_difficulty,
            channel = message.channel,
            wire_json = message.wire_json,
        )
    }

    suspend fun byConversation(conversationId: String): List<Message> =
        withContext(dispatchers.io) {
            databaseManager.getDb().messageQueries.selectByConversation(conversationId).executeAsList()
        }

    suspend fun byId(id: String): Message? = withContext(dispatchers.io) {
        databaseManager.getDb().messageQueries.selectById(id).executeAsOneOrNull()
    }

    suspend fun conversationIds(): List<String> = withContext(dispatchers.io) {
        databaseManager.getDb().messageQueries.selectConversationIds().executeAsList()
    }

    /** Most-recent [limit] rows for a conversation, returned oldest-first (ready to append). */
    suspend fun recentByConversation(conversationId: String, limit: Long): List<Message> =
        withContext(dispatchers.io) {
            databaseManager.getDb().messageQueries
                .selectRecentByConversation(conversationId, limit).executeAsList().asReversed()
        }

    fun observeByConversation(conversationId: String): Flow<List<Message>> = flow {
        val db = databaseManager.getDb()
        emitAll(db.messageQueries.selectByConversation(conversationId).asFlow().mapToList(dispatchers.io))
    }

    suspend fun updateDeliveryStatus(id: String, status: String?) = withContext(dispatchers.io) {
        databaseManager.getDb().messageQueries.updateDeliveryStatus(status, id)
    }

    suspend fun deleteById(id: String) = withContext(dispatchers.io) {
        databaseManager.getDb().messageQueries.deleteById(id)
    }

    suspend fun deleteByConversation(conversationId: String) = withContext(dispatchers.io) {
        databaseManager.getDb().messageQueries.deleteByConversation(conversationId)
    }

    suspend fun deleteAll() = withContext(dispatchers.io) {
        databaseManager.getDb().messageQueries.deleteAll()
    }

    /** Retention: delete every message with a timestamp strictly older than [cutoffMillis]. */
    suspend fun deleteOlderThan(cutoffMillis: Long) = withContext(dispatchers.io) {
        databaseManager.getDb().messageQueries.deleteOlderThan(cutoffMillis)
    }

    /**
     * Retention: for each conversation, keep only the newest [keepPerConversation] messages and delete
     * the older tail. Runs one transaction over all conversations so the whole sweep is a single fsync.
     */
    suspend fun enforcePerConversationCap(keepPerConversation: Long) = withContext(dispatchers.io) {
        val queries = databaseManager.getDb().messageQueries
        queries.transaction {
            queries.selectConversationIds().executeAsList().forEach { conversationId ->
                queries.deleteBeyondCapForConversation(conversationId, keepPerConversation)
            }
        }
    }
}
