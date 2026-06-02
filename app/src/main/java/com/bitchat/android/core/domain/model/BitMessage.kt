@file:OptIn(ExperimentalTime::class)

package com.bitchat.android.core.domain.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Тип содержимого сообщения. */
enum class MessageType { TEXT, AUDIO, IMAGE, FILE }

/**
 * Лёгкая ссылка на отправителя — чтобы UI не ходил в репозитории за именем на каждый рендер.
 * [peerId] = null для системных сообщений.
 */
data class SenderRef(
    val peerId: PeerId?,
    val displayName: String,
) {
    companion object {
        const val SYSTEM_NAME = "system"
        val SYSTEM = SenderRef(peerId = null, displayName = SYSTEM_NAME)
    }
}

/**
 * Доменная сущность сообщения мессенджера (имя — bitMessage). Чистая: НЕ содержит бинарной
 * упаковки (wire-кодек `toBinaryPayload` остаётся в data-mapper'е ради байт-совместимости).
 */
data class BitMessage(
    val id: String,
    val conversationId: ConversationId,
    val sender: SenderRef,
    val content: String,
    val timestamp: Instant,
    val type: MessageType = MessageType.TEXT,
    val isMine: Boolean = false,
    val isRelay: Boolean = false,
    val mentions: List<String> = emptyList(),
    val deliveryStatus: DeliveryStatus? = null,
    val attachment: Attachment? = null,
    val powDifficulty: Int? = null,
) {
    val isSystem: Boolean get() = sender.peerId == null
}
