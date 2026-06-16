@file:OptIn(ExperimentalTime::class)

package com.app.domain.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Stable cross-session identity: Noise key (hex) + optional Nostr npub. */
data class PeerIdentity(
    val noiseKeyHex: String,
    val nostrNpub: String? = null,
)

/**
 * Contact / favorite (Noise<->Nostr bridge). Ported from FavoriteRelationship, without ByteArray.
 */
data class Contact(
    val identity: PeerIdentity,
    val nickname: String,
    val isFavorite: Boolean,
    val theyFavoritedUs: Boolean,
    val favoritedAt: Instant,
    val lastUpdated: Instant,
    val isBlocked: Boolean = false,
    val isVerified: Boolean = false,
) {
    /** Mutual favorite — the condition for routing over Nostr when mesh is offline. */
    val isMutual: Boolean get() = isFavorite && theyFavoritedUs
}
