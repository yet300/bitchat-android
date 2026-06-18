@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)

package com.app.domain.usecase

import com.app.domain.model.BitMessage
import com.app.domain.model.ConversationId
import com.app.domain.model.DeliveryStatus
import com.app.domain.model.SenderRef
import com.app.domain.repository.MessageRepository
import com.app.domain.repository.MessageTransport
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Sends a message: routes by conversation kind and adds a local echo to the timeline.
 * SRP: a single action; transport choice (mesh/Nostr) is hidden behind [MessageTransport].
 *
 * @param clock time source (injected for deterministic tests)
 * @param newId message-id generator (injected for tests)
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
                // Echo locally then post: the geohash ingest skips our own relayed events, so the
                // echo is the only local copy (no duplication). Mirrors the broadcast kinds above.
                messages.append(target, echo(id, target, sender, content, now, mentions))
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
