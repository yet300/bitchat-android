@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)

package com.app.domain.usecase

import com.app.domain.model.Attachment
import com.app.domain.model.AttachmentKind
import com.app.domain.model.BitMessage
import com.app.domain.model.ConversationId
import com.app.domain.model.DeliveryStatus
import com.app.domain.model.MessageType
import com.app.domain.model.SenderRef
import com.app.domain.repository.MessageRepository
import com.app.domain.repository.MessageTransport
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Result of an attachment send. */
sealed interface AttachmentSendResult {
    data object Sent : AttachmentSendResult
    data class RejectedTooLarge(val sizeBytes: Long, val maxBytes: Long) : AttachmentSendResult
}

/**
 * Send a media attachment (voice/image/file): enforce the size limit, add a local echo seeded
 * with transfer progress, then hand off to the transport.
 */
class SendAttachmentUseCase(
    private val transport: MessageTransport,
    private val messages: MessageRepository,
    private val clock: Clock = Clock.System,
    private val newId: () -> String = { Uuid.random().toString().uppercase() },
    private val maxBytes: Long = MAX_ATTACHMENT_BYTES,
) {

    suspend operator fun invoke(
        target: ConversationId,
        attachment: Attachment,
        sender: SenderRef,
    ): AttachmentSendResult {
        val size = attachment.sizeBytes
        if (size != null && size > maxBytes) {
            return AttachmentSendResult.RejectedTooLarge(size, maxBytes)
        }

        val id = newId()
        val echo = BitMessage(
            id = id,
            conversationId = target,
            sender = sender,
            content = attachment.ref,
            timestamp = clock.now(),
            type = attachment.kind.toMessageType(),
            isMine = true,
            attachment = attachment,
            // Seed progress so delivery/transfer UI renders immediately (matches current behavior).
            deliveryStatus = DeliveryStatus.PartiallyDelivered(reached = 0, total = 100),
        )
        messages.append(target, echo)
        transport.sendAttachment(attachment, target, id)
        return AttachmentSendResult.Sent
    }

    private fun AttachmentKind.toMessageType(): MessageType = when (this) {
        AttachmentKind.AUDIO -> MessageType.AUDIO
        AttachmentKind.IMAGE -> MessageType.IMAGE
        AttachmentKind.FILE -> MessageType.FILE
    }

    companion object {
        const val MAX_ATTACHMENT_BYTES = 50L * 1024 * 1024 // 50 MB, matches MediaSendingManager
    }
}
