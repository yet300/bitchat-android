package com.bitchat.data.mapper

import com.bitchat.data.dto.IdentityAnnouncementDto
import com.bitchat.domain.model.IdentityAnnouncement

/**
 * Extension functions for mapping between IdentityAnnouncement domain model and DTO
 */

/**
 * Convert IdentityAnnouncement domain model to DTO
 */
fun IdentityAnnouncement.toDto(): IdentityAnnouncementDto {
    return IdentityAnnouncementDto(
        nickname = this.nickname,
        noisePublicKey = this.noisePublicKey,
        signingPublicKey = this.signingPublicKey
    )
}

/**
 * Convert IdentityAnnouncementDto to domain model
 */
fun IdentityAnnouncementDto.toDomain(): IdentityAnnouncement {
    return IdentityAnnouncement(
        nickname = this.nickname,
        noisePublicKey = this.noisePublicKey,
        signingPublicKey = this.signingPublicKey
    )
}