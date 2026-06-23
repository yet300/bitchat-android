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
 * Message timeline access. Returns the generated [Message] entity; mapping to the domain `BitMessage`
 * stays in the data layer. `conversationId` uses the AppStateStore key scheme
 * (public / private:<peer> / channel:<tag> / geo:<gh>).
 */
class MessageDao(
    private val databaseManager: DatabaseManager,
    private val dispatchers: AppDispatchers,
) {

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
        )
    }

    suspend fun byConversation(conversationId: String): List<Message> =
        withContext(dispatchers.io) {
            databaseManager.getDb().messageQueries.selectByConversation(conversationId).executeAsList()
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
}
