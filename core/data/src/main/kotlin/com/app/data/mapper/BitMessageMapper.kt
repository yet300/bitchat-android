@file:OptIn(ExperimentalTime::class)

package com.app.data.mapper

import com.app.domain.model.Attachment
import com.app.domain.model.AttachmentKind
import com.app.domain.model.BitMessage
import com.app.domain.model.ConversationId
import com.app.domain.model.MessageType
import com.app.domain.model.PeerId
import com.app.domain.model.SenderRef
import com.app.domain.model.TransferState
import com.app.transport.model.BitchatMessage
import com.app.transport.model.BitchatMessageType
import java.io.File
import java.util.Locale
import kotlin.time.ExperimentalTime
import com.app.domain.model.DeliveryStatus as DomainDeliveryStatus
import com.app.transport.model.DeliveryStatus as WireDeliveryStatus

/**
 * Maps between the wire DTO ([BitchatMessage]) and the domain entity ([BitMessage]).
 *
 * The conversation id is supplied by the caller (the storage bucket the message lives in — public /
 * private[peerID] / channel[name]), since the wire DTO does not carry it. Ownership ([BitMessage.isMine])
 * needs my current peer id, also passed in (it comes from the identity layer once that lands).
 * Binary packing stays in [BitchatMessage.toBinaryPayload] (transport) to preserve byte compatibility.
 */
internal fun BitchatMessage.toDomain(conversationId: ConversationId, myPeerId: String?): BitMessage =
    BitMessage(
        id = id,
        conversationId = conversationId,
        sender = SenderRef(
            peerId = senderPeerID?.let(::PeerId),
            displayName = sender,
        ),
        content = content,
        timestamp = timestamp,
        type = type.toDomainType(),
        isMine = myPeerId != null && senderPeerID == myPeerId,
        isRelay = isRelay,
        mentions = mentions ?: emptyList(),
        deliveryStatus = deliveryStatus?.toDomainStatus(),
        attachment = type.toAttachment(content),
        powDifficulty = powDifficulty,
    )

/**
 * Build a domain [Attachment] for an incoming media message. For media types the decoded local
 * file path already lives in [content]; size is read from disk (null if the file is gone). TEXT and
 * blank-content media carry no attachment.
 */
private fun BitchatMessageType.toAttachment(content: String): Attachment? {
    val kind = when (this) {
        BitchatMessageType.Audio -> AttachmentKind.AUDIO
        BitchatMessageType.Image -> AttachmentKind.IMAGE
        BitchatMessageType.File -> AttachmentKind.FILE
        BitchatMessageType.Message -> return null
    }
    if (content.isBlank()) return null
    val size = runCatching { File(content).takeIf { it.isFile }?.length() }.getOrNull()
    return Attachment(
        kind = kind,
        ref = content,
        mime = content.mimeFromExtension(),
        sizeBytes = size,
        transfer = TransferState.Done,
    )
}

private fun String.mimeFromExtension(): String? = when (substringAfterLast('.', "").lowercase(Locale.US)) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "heic" -> "image/heic"
    "m4a" -> "audio/mp4"
    "aac" -> "audio/aac"
    "mp3" -> "audio/mpeg"
    "opus" -> "audio/opus"
    "ogg" -> "audio/ogg"
    "wav" -> "audio/wav"
    "pdf" -> "application/pdf"
    "" -> null
    else -> null
}

internal fun BitMessage.toWire(): BitchatMessage =
    BitchatMessage(
        id = id,
        sender = sender.displayName,
        content = content,
        type = type.toWireType(),
        timestamp = timestamp,
        isRelay = isRelay,
        isPrivate = conversationId is ConversationId.Private,
        senderPeerID = sender.peerId?.raw,
        mentions = mentions.takeIf { it.isNotEmpty() },
        channel = (conversationId as? ConversationId.Channel)?.tag,
        deliveryStatus = deliveryStatus?.toWireStatus(),
        powDifficulty = powDifficulty,
    )

private fun BitchatMessageType.toDomainType(): MessageType = when (this) {
    BitchatMessageType.Message -> MessageType.TEXT
    BitchatMessageType.Audio -> MessageType.AUDIO
    BitchatMessageType.Image -> MessageType.IMAGE
    BitchatMessageType.File -> MessageType.FILE
}

private fun MessageType.toWireType(): BitchatMessageType = when (this) {
    MessageType.TEXT -> BitchatMessageType.Message
    MessageType.AUDIO -> BitchatMessageType.Audio
    MessageType.IMAGE -> BitchatMessageType.Image
    MessageType.FILE -> BitchatMessageType.File
}

private fun WireDeliveryStatus.toDomainStatus(): DomainDeliveryStatus = when (this) {
    is WireDeliveryStatus.Sending -> DomainDeliveryStatus.Sending
    is WireDeliveryStatus.Sent -> DomainDeliveryStatus.Sent
    is WireDeliveryStatus.Delivered -> DomainDeliveryStatus.Delivered(to, at)
    is WireDeliveryStatus.Read -> DomainDeliveryStatus.Read(by, at)
    is WireDeliveryStatus.Failed -> DomainDeliveryStatus.Failed(reason)
    is WireDeliveryStatus.PartiallyDelivered -> DomainDeliveryStatus.PartiallyDelivered(reached, total)
}

internal fun DomainDeliveryStatus.toWireStatus(): WireDeliveryStatus = when (this) {
    is DomainDeliveryStatus.Sending -> WireDeliveryStatus.Sending
    is DomainDeliveryStatus.Sent -> WireDeliveryStatus.Sent
    is DomainDeliveryStatus.Delivered -> WireDeliveryStatus.Delivered(to, at)
    is DomainDeliveryStatus.Read -> WireDeliveryStatus.Read(by, at)
    is DomainDeliveryStatus.Failed -> WireDeliveryStatus.Failed(reason)
    is DomainDeliveryStatus.PartiallyDelivered -> WireDeliveryStatus.PartiallyDelivered(reached, total)
}
