@file:OptIn(ExperimentalTime::class)

package com.app.data

import com.app.common.serialization.JsonConfig
import com.app.database.Message
import com.app.database.dao.MessageDao
import com.app.transport.model.BitchatMessage
import com.app.transport.model.DeliveryStatus
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.ExperimentalTime

/**
 * DB-backed [MessagePersistence]: mirrors the timelines into the encrypted `message` table via
 * [MessageDao]. The full wire message is stored as JSON ([Message.wire_json]) for lossless
 * reconstruction; the other columns are its searchable projection.
 */
@SingleIn(AppScope::class)
@Inject
class DbMessagePersistence(
    private val messageDao: MessageDao,
    private val scope: CoroutineScope,
) : MessagePersistence {

    override fun persist(conversationKey: String, message: BitchatMessage) {
        scope.launch { messageDao.upsert(message.toEntity(conversationKey)) }
    }

    override fun updateStatus(messageId: String, status: DeliveryStatus) {
        scope.launch {
            val row = messageDao.byId(messageId) ?: return@launch
            val wire = row.wire_json?.let(::decode) ?: return@launch
            messageDao.upsert(wire.copy(deliveryStatus = status).toEntity(row.conversation_id))
        }
    }

    override fun delete(messageId: String) {
        scope.launch { messageDao.deleteById(messageId) }
    }

    override fun clear(conversationKey: String?) {
        scope.launch {
            if (conversationKey == null) messageDao.deleteAll() else messageDao.deleteByConversation(conversationKey)
        }
    }

    override suspend fun load(perConversationLimit: Long): Map<String, List<BitchatMessage>> =
        messageDao.conversationIds().associateWith { id ->
            messageDao.recentByConversation(id, perConversationLimit).mapNotNull { it.wire_json?.let(::decode) }
        }

    private fun decode(json: String): BitchatMessage? =
        runCatching { JsonConfig.json.decodeFromString(BitchatMessage.serializer(), json) }.getOrNull()

    private fun BitchatMessage.toEntity(conversationKey: String): Message = Message(
        id = id,
        conversation_id = conversationKey,
        sender_peer_id = senderPeerID,
        sender_name = sender,
        content = content,
        timestamp = timestamp.toEpochMilliseconds(),
        type = type.name,
        is_mine = 0L,
        is_relay = if (isRelay) 1L else 0L,
        mentions = null,
        delivery_status = null,
        attachment_path = null,
        attachment_type = null,
        pow_difficulty = powDifficulty?.toLong(),
        channel = channel,
        wire_json = JsonConfig.json.encodeToString(BitchatMessage.serializer(), this),
    )
}
