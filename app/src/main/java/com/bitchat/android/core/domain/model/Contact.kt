@file:OptIn(ExperimentalTime::class)

package com.bitchat.android.core.domain.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Стабильная межсессионная личность: Noise-ключ (hex) + опциональный Nostr npub. */
data class PeerIdentity(
    val noiseKeyHex: String,
    val nostrNpub: String? = null,
)

/**
 * Контакт/избранное (мост Noise↔Nostr). Порт из FavoriteRelationship, без ByteArray.
 */
data class Contact(
    val identity: PeerIdentity,
    val nickname: String,
    val isFavorite: Boolean,
    val theyFavoritedUs: Boolean,
    val favoritedAt: Instant,
    val lastUpdated: Instant,
) {
    /** Взаимное избранное — условие маршрутизации через Nostr при оффлайн-mesh. */
    val isMutual: Boolean get() = isFavorite && theyFavoritedUs
}
