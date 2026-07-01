@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.app.data

import com.app.domain.model.Contact
import com.app.domain.model.Fingerprint
import com.app.domain.model.PeerId
import com.app.domain.model.PeerIdentity
import com.app.domain.repository.ContactRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Minimal [ContactRepository] for sink tests; only block state is meaningful here. */
internal class FakeContactRepository(
    private val blocked: Set<PeerId> = emptySet(),
) : ContactRepository {
    override fun observeFavorites(): Flow<Set<Fingerprint>> = MutableStateFlow(emptySet())
    override fun observeContacts(): Flow<List<Contact>> = MutableStateFlow(emptyList())
    override fun observeVerified(noiseKeyHex: String): Flow<Boolean> = MutableStateFlow(false)
    override suspend fun toggleFavorite(peer: PeerId) = Unit
    override suspend fun isFavorite(peer: PeerId): Boolean = false
    override suspend fun setBlocked(peer: PeerId, blocked: Boolean) = Unit
    override fun isBlocked(peer: PeerId): Boolean = peer in blocked
    override suspend fun contact(identity: PeerIdentity): Contact? = null
    override suspend fun noiseKeyHexForNostrAlias(alias: PeerId): String? = null
    override suspend fun clearAll() = Unit
}
