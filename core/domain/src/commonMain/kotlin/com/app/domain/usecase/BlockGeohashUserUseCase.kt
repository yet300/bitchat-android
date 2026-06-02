package com.app.domain.usecase

import com.app.domain.repository.GeohashRepository

/**
 * Block a geohash participant by their display nickname (resolve nickname -> pubkey, then block).
 */
class BlockGeohashUserUseCase(
    private val geohash: GeohashRepository,
) {
    /** Returns true if the nickname was found in the current geo-channel and blocked. */
    suspend operator fun invoke(nickname: String): Boolean {
        val pubkey = geohash.pubkeyForNickname(nickname) ?: return false
        geohash.setBlocked(pubkey, blocked = true)
        return true
    }
}
