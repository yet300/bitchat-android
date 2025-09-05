package com.bitchat.data.mapper

import com.bitchat.data.dto.BitchatMessageDto
import com.bitchat.data.dto.DeliveryStatusDto
import com.bitchat.domain.model.BitchatMessage
import com.bitchat.domain.model.DeliveryStatus

/**
 * Extension functions for mapping between BitchatMessage domain model and DTO
 */

/**
 * Convert BitchatMessage domain model to DTO
 */
fun BitchatMessage.toDto(): BitchatMessageDto {
    return BitchatMessageDto(
        id = this.id,
        sender = this.sender,
        content = this.content,
        timestamp = this.timestamp,
        isRelay = this.isRelay,
        originalSender = this.originalSender,
        isPrivate = this.isPrivate,
        recipientNickname = this.recipientNickname,
        senderPeerID = this.senderPeerID,
        mentions = this.mentions,
        channel = this.channel,
        encryptedContent = this.encryptedContent,
        isEncrypted = this.isEncrypted,
        deliveryStatus = this.deliveryStatus?.toDto(),
        powDifficulty = this.powDifficulty
    )
}

/**
 * Convert BitchatMessageDto to domain model
 */
fun BitchatMessageDto.toDomain(): BitchatMessage {
    return BitchatMessage(
        id = this.id,
        sender = this.sender,
        content = this.content,
        timestamp = this.timestamp,
        isRelay = this.isRelay,
        originalSender = this.originalSender,
        isPrivate = this.isPrivate,
        recipientNickname = this.recipientNickname,
        senderPeerID = this.senderPeerID,
        mentions = this.mentions,
        channel = this.channel,
        encryptedContent = this.encryptedContent,
        isEncrypted = this.isEncrypted,
        deliveryStatus = this.deliveryStatus?.toDomain(),
        powDifficulty = this.powDifficulty
    )
}

/**
 * Convert DeliveryStatus domain to DTO
 */
fun DeliveryStatus.toDto(): DeliveryStatusDto {
    return when (this) {
        is DeliveryStatus.Sending -> DeliveryStatusDto.Sending
        is DeliveryStatus.Sent -> DeliveryStatusDto.Sent
        is DeliveryStatus.Delivered -> DeliveryStatusDto.Delivered(this.to, this.at)
        is DeliveryStatus.Read -> DeliveryStatusDto.Read(this.by, this.at)
        is DeliveryStatus.Failed -> DeliveryStatusDto.Failed(this.reason)
        is DeliveryStatus.PartiallyDelivered -> DeliveryStatusDto.PartiallyDelivered(this.reached, this.total)
    }
}

/**
 * Convert DeliveryStatus DTO to domain
 */
fun DeliveryStatusDto.toDomain(): DeliveryStatus {
    return when (this) {
        is DeliveryStatusDto.Sending -> DeliveryStatus.Sending
        is DeliveryStatusDto.Sent -> DeliveryStatus.Sent
        is DeliveryStatusDto.Delivered -> DeliveryStatus.Delivered(this.to, this.at)
        is DeliveryStatusDto.Read -> DeliveryStatus.Read(this.by, this.at)
        is DeliveryStatusDto.Failed -> DeliveryStatus.Failed(this.reason)
        is DeliveryStatusDto.PartiallyDelivered -> DeliveryStatus.PartiallyDelivered(this.reached, this.total)
    }
}