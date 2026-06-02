package com.app.domain.usecase

import com.app.domain.model.ConversationId
import com.app.domain.repository.GeohashRepository

/** How a geohash DM target is identified. */
sealed interface GeohashDmTarget {
    data class Pubkey(val hex: String) : GeohashDmTarget
    data class Nickname(val value: String) : GeohashDmTarget
    data class ShortId(val value: String) : GeohashDmTarget
}

/**
 * Resolve a geohash participant (by pubkey / nickname / short id) and open a DM with them.
 * Returns the conversation id, or null if the target could not be resolved.
 */
class StartGeohashDmUseCase(
    private val geohash: GeohashRepository,
) {
    suspend operator fun invoke(target: GeohashDmTarget): ConversationId? {
        val pubkey = when (target) {
            is GeohashDmTarget.Pubkey -> target.hex
            is GeohashDmTarget.Nickname -> geohash.pubkeyForNickname(target.value)
            is GeohashDmTarget.ShortId -> geohash.pubkeyForShortId(target.value)
        } ?: return null
        return geohash.startDirectMessage(pubkey)
    }
}
