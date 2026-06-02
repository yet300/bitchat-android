@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)

package com.bitchat.android.core.domain.usecase

import com.bitchat.android.core.domain.model.BitMessage
import com.bitchat.android.core.domain.model.ConversationId
import com.bitchat.android.core.domain.model.DeliveryStatus
import com.bitchat.android.core.domain.model.SenderRef
import com.bitchat.android.core.domain.repository.MessageRepository
import com.bitchat.android.core.domain.repository.MessageTransport
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Отправка сообщения: маршрутизация по виду диалога + локальное эхо в ленту.
 * SRP: одно действие; выбор транспорта (mesh/Nostr) скрыт за [MessageTransport].
 *
 * @param clock источник времени (инъекция ради детерминированных тестов)
 * @param newId генератор id сообщения (инъекция ради тестов)
 */
class SendMessageUseCase(
    private val transport: MessageTransport,
    private val messages: MessageRepository,
    private val clock: Clock = Clock.System,
    private val newId: () -> String = { Uuid.random().toString().uppercase() },
) {

    suspend operator fun invoke(
        target: ConversationId,
        content: String,
        sender: SenderRef,
        mentions: List<String> = emptyList(),
    ) {
        if (content.isBlank()) return
        val id = newId()
        val now = clock.now()

        when (target) {
            is ConversationId.PublicMesh -> {
                messages.append(target, echo(id, target, sender, content, now, mentions))
                transport.sendPublic(content, mentions, channel = null)
            }

            is ConversationId.Channel -> {
                messages.append(target, echo(id, target, sender, content, now, mentions))
                transport.sendPublic(content, mentions, channel = target.tag)
            }

            is ConversationId.Private -> {
                messages.append(
                    target,
                    echo(id, target, sender, content, now, mentions, status = DeliveryStatus.Sending),
                )
                transport.sendPrivate(content, target.peer, recipientNickname = null, messageId = id)
            }

            is ConversationId.Geohash -> {
                // Локальное эхо для гео-чата добавляет транспорт (как сейчас в GeohashViewModel),
                // чтобы не задвоить — поэтому здесь только отправка.
                transport.sendGeohash(content, target.channel, sender.displayName)
            }
        }
    }

    private fun echo(
        id: String,
        target: ConversationId,
        sender: SenderRef,
        content: String,
        now: Instant,
        mentions: List<String>,
        status: DeliveryStatus? = null,
    ) = BitMessage(
        id = id,
        conversationId = target,
        sender = sender,
        content = content,
        timestamp = now,
        isMine = true,
        mentions = mentions,
        deliveryStatus = status,
    )
}
