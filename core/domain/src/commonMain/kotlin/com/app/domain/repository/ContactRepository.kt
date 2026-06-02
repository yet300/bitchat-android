package com.app.domain.repository

import com.app.domain.model.Contact
import com.app.domain.model.Fingerprint
import com.app.domain.model.PeerId
import com.app.domain.model.PeerIdentity
import kotlinx.coroutines.flow.Flow

/**
 * Favorites and blocking by mesh fingerprint, plus Noise<->Nostr resolution.
 */
interface ContactRepository {

    /** Stream of the favorite-fingerprints set (for reactive UI). */
    fun observeFavorites(): Flow<Set<Fingerprint>>

    /** Toggle favorite for a peer. */
    suspend fun toggleFavorite(peer: PeerId)

    suspend fun isFavorite(peer: PeerId): Boolean

    suspend fun setBlocked(peer: PeerId, blocked: Boolean)

    suspend fun isBlocked(peer: PeerId): Boolean

    /** Contact by stable identity (for routing/mutuality). */
    suspend fun contact(identity: PeerIdentity): Contact?

    /** Noise key (hex) for a Nostr alias `nostr_<pub16>` — for canonical-peer resolution. */
    suspend fun noiseKeyHexForNostrAlias(alias: PeerId): String?
}
