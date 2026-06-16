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

    /**
     * Stream of our contacts (people we favorited), each annotated with its current
     * [blocked][Contact.isBlocked] state — the data source for the contacts screen.
     */
    fun observeContacts(): Flow<List<Contact>>

    /** Stream of whether the identity with this Noise key (hex) is verified (for the chat header). */
    fun observeVerified(noiseKeyHex: String): Flow<Boolean>

    /** Toggle favorite for a peer. */
    suspend fun toggleFavorite(peer: PeerId)

    suspend fun isFavorite(peer: PeerId): Boolean

    suspend fun setBlocked(peer: PeerId, blocked: Boolean)

    suspend fun isBlocked(peer: PeerId): Boolean

    /** Contact by stable identity (for routing/mutuality). */
    suspend fun contact(identity: PeerIdentity): Contact?

    /** Noise key (hex) for a Nostr alias `nostr_<pub16>` — for canonical-peer resolution. */
    suspend fun noiseKeyHexForNostrAlias(alias: PeerId): String?

    /** Clear all favorites and blocked entries (used by the panic wipe). */
    suspend fun clearAll()
}
