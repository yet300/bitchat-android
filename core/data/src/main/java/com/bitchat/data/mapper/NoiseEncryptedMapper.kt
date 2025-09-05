package com.bitchat.data.mapper

import com.bitchat.data.dto.NoisePayloadDto
import com.bitchat.data.dto.PrivateMessagePacketDto
import com.bitchat.data.dto.ReadReceiptDto
import com.bitchat.domain.model.NoisePayload
import com.bitchat.domain.model.PrivateMessagePacket
import com.bitchat.domain.model.ReadReceipt

/**
 * Extension functions for mapping between NoiseEncrypted domain models and DTOs
 */

/**
 * Convert NoisePayload domain to DTO
 */
fun NoisePayload.toDto(): NoisePayloadDto {
    return NoisePayloadDto(
        type = this.type,
        data = this.data
    )
}

/**
 * Convert NoisePayload DTO to domain
 */
fun NoisePayloadDto.toDomain(): NoisePayload {
    return NoisePayload(
        type = this.type,
        data = this.data
    )
}

/**
 * Convert PrivateMessagePacket domain to DTO
 */
fun PrivateMessagePacket.toDto(): PrivateMessagePacketDto {
    return PrivateMessagePacketDto(
        messageID = this.messageID,
        content = this.content
    )
}

/**
 * Convert PrivateMessagePacket DTO to domain
 */
fun PrivateMessagePacketDto.toDomain(): PrivateMessagePacket {
    return PrivateMessagePacket(
        messageID = this.messageID,
        content = this.content
    )
}

/**
 * Convert ReadReceipt domain to DTO
 */
fun ReadReceipt.toDto(): ReadReceiptDto {
    return ReadReceiptDto(
        originalMessageID = this.originalMessageID,
        readerPeerID = this.readerPeerID
    )
}

/**
 * Convert ReadReceipt DTO to domain
 */
fun ReadReceiptDto.toDomain(): ReadReceipt {
    return ReadReceipt(
        originalMessageID = this.originalMessageID,
        readerPeerID = this.readerPeerID
    )
}