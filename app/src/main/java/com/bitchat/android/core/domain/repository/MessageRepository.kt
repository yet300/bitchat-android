package com.bitchat.android.core.domain.repository

import com.bitchat.android.core.domain.model.BitMessage
import com.bitchat.android.core.domain.model.ConversationId
import com.bitchat.android.core.domain.model.DeliveryStatus
import kotlinx.coroutines.flow.Flow

/**
 * Хранилище ленты сообщений (источник правды для UI). Намеренно отделено от [MessageTransport]
 * (исходящая отправка): разные клиенты и причины меняться (ISP/SRP).
 */
interface MessageRepository {

    /** Поток сообщений одного диалога. */
    fun observeMessages(id: ConversationId): Flow<List<BitMessage>>

    /** Текущий снимок сообщений диалога (например, для рассылки read-receipts). */
    suspend fun snapshot(id: ConversationId): List<BitMessage>

    /** Добавить сообщение (локальное эхо или входящее). */
    suspend fun append(id: ConversationId, message: BitMessage)

    /**
     * Обновить статус доставки по id сообщения. Контракт: статус не понижается
     * (см. [DeliveryStatusPolicy][com.bitchat.android.core.domain.model.DeliveryStatusPolicy]).
     */
    suspend fun updateDeliveryStatus(messageId: String, status: DeliveryStatus)

    /** Удалить сообщение из всех мест. */
    suspend fun remove(messageId: String)

    /** Очистить ленту диалога. */
    suspend fun clear(id: ConversationId)
}
