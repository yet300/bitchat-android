@file:OptIn(ExperimentalTime::class)

package com.app.data.mapper

import com.app.domain.model.BitMessage
import com.app.domain.model.ConversationId
import com.app.domain.model.MessageType
import com.app.domain.model.PeerId
import com.app.domain.model.SenderRef
import com.app.transport.model.BitchatMessage
import com.app.transport.model.BitchatMessageType
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
        attachment = null, // attachments are carried separately (FileSharingManager); not mapped here yet
        powDifficulty = powDifficulty,
    )

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
