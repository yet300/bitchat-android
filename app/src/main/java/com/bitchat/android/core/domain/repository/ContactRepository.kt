package com.bitchat.android.core.domain.repository

import com.bitchat.android.core.domain.model.Contact
import com.bitchat.android.core.domain.model.Fingerprint
import com.bitchat.android.core.domain.model.PeerId
import com.bitchat.android.core.domain.model.PeerIdentity
import kotlinx.coroutines.flow.Flow

/**
 * Избранное (favorites) и блокировки по mesh-отпечатку, плюс резолв Noise↔Nostr.
 */
interface ContactRepository {

    /** Поток множества избранных отпечатков (для реактивного UI). */
    fun observeFavorites(): Flow<Set<Fingerprint>>

    /** Переключить избранное для пира. */
    suspend fun toggleFavorite(peer: PeerId)

    suspend fun isFavorite(peer: PeerId): Boolean

    suspend fun setBlocked(peer: PeerId, blocked: Boolean)

    suspend fun isBlocked(peer: PeerId): Boolean

    /** Контакт по стабильной личности (для маршрутизации/взаимности). */
    suspend fun contact(identity: PeerIdentity): Contact?

    /** Noise-ключ (hex) для Nostr-алиаса `nostr_<pub16>` — для резолва канонического пира. */
    suspend fun noiseKeyHexForNostrAlias(alias: PeerId): String?
}
