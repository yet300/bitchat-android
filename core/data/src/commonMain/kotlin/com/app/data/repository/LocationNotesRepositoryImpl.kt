package com.app.data.repository

import com.app.domain.model.LocationNote
import com.app.domain.repository.LocationNotesRepository
import com.app.transport.nostr.LocationNotesManager
import com.app.transport.nostr.NostrIdentityBridge
import com.app.transport.nostr.NostrRelayManager
import com.app.transport.nostr.RelayDirectory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Re-homes the location-notes wiring the legacy `LocationNotesInitializer` did from MainActivity:
 * binds the graph-owned [LocationNotesManager] to the Nostr relay/identity ports and exposes the
 * feed through the domain [LocationNotesRepository]. The manager owns subscription/dedup/state.
 */
@SingleIn(AppScope::class)
@Inject
internal class LocationNotesRepositoryImpl(
    private val manager: LocationNotesManager,
    private val relayManager: NostrRelayManager,
    private val relayDirectory: RelayDirectory,
    private val nostrIdentityBridge: NostrIdentityBridge,
) : LocationNotesRepository {

    init {
        manager.initialize(
            relayManager = { relayManager },
            subscribe = { filter, id, handler ->
                val geohash = filter.getGeohash()
                if (geohash == null) {
                    id
                } else {
                    relayManager.subscribeForGeohash(
                        geohash = geohash,
                        filter = filter,
                        relayDirectory = relayDirectory,
                        id = id,
                        handler = handler,
                        includeDefaults = true,
                        nRelays = GEO_RELAYS,
                    )
                }
            },
            unsubscribe = { relayManager.unsubscribe(it) },
            sendEvent = { event, relayUrls ->
                if (relayUrls != null) relayManager.sendEvent(event, relayUrls) else relayManager.sendEvent(event)
            },
            deriveIdentity = { nostrIdentityBridge.deriveIdentity(it) },
        )
    }

    override fun observeNotes(): Flow<List<LocationNote>> =
        manager.notes.map { notes -> notes.map { it.toDomain() } }

    override fun observeLoading(): Flow<Boolean> =
        manager.initialLoadComplete.map { !it }

    override fun select(geohash: String) = manager.setGeohash(geohash)

    override suspend fun send(content: String, nickname: String?) =
        manager.send(content, nickname, relayDirectory)

    private fun LocationNotesManager.Note.toDomain() = LocationNote(
        id = id,
        authorName = displayName,
        content = content,
        createdAtEpochSeconds = createdAt,
    )

    private companion object {
        const val GEO_RELAYS = 5
    }
}
