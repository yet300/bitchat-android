package com.bitchat.data.mapper

import com.bitchat.data.dto.BitchatPacketDto
import com.bitchat.domain.protocol.BitchatPacket

/**
 * Extension functions for mapping between BitchatPacket domain model and DTO
 */

/**
 * Convert BitchatPacket domain model to DTO
 */
fun BitchatPacket.toDto(): BitchatPacketDto {
    return BitchatPacketDto(
        version = this.version,
        type = this.type,
        senderID = this.senderID,
        recipientID = this.recipientID,
        timestamp = this.timestamp,
        payload = this.payload,
        signature = this.signature,
        ttl = this.ttl
    )
}

/**
 * Convert BitchatPacketDto to domain model
 */
fun BitchatPacketDto.toDomain(): BitchatPacket {
    return BitchatPacket(
        version = this.version,
        type = this.type,
        senderID = this.senderID,
        recipientID = this.recipientID,
        timestamp = this.timestamp,
        payload = this.payload,
        signature = this.signature,
        ttl = this.ttl
    )
}