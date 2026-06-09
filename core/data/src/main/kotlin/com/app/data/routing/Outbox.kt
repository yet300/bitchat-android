package com.app.data.routing

import com.app.data.favorites.FavoritesChangeListener
import com.app.data.favorites.FavoritesPersistenceService
import com.app.transport.routing.OutgoingEnvelope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory outbox for envelopes that could not be routed immediately.
 *
 * Listens for favorites changes so queued Nostr-bound messages can be flushed
 * as soon as a mutual-favorite mapping becomes available.
 * Injected as a graph singleton; [RouteSelector] wires the flush callback on init.
 */
@SingleIn(AppScope::class)
@Inject
internal class Outbox(
    private val favoritesService: FavoritesPersistenceService,
) : FavoritesChangeListener {

    private val queued = ConcurrentHashMap<String, MutableList<OutgoingEnvelope>>()

    /**
     * Called by [RouteSelector] during its own initialisation to hook flush-on-reachable.
     * Nullable so Outbox compiles independently of RouteSelector.
     */
    var onFlushNeeded: ((peerID: String) -> Unit)? = null

    init {
        favoritesService.addListener(this)
    }

    /** Enqueues [envelope] for later delivery. */
    fun enqueue(envelope: OutgoingEnvelope) {
        queued.getOrPut(envelope.peerID) { mutableListOf() }.add(envelope)
    }

    /** Removes and returns all envelopes queued for [peerID]. */
    fun drain(peerID: String): List<OutgoingEnvelope> =
        queued.remove(peerID) ?: emptyList()

    // FavoritesChangeListener -------------------------------------------------

    override fun onFavoriteChanged(noiseKeyHex: String) {
        // A new Noise<->Nostr mapping appeared; try to flush any pending envelopes.
        onFlushNeeded?.invoke(noiseKeyHex)
        // Also try the 16-hex short peerID (commonly used in mesh addressing).
        onFlushNeeded?.invoke(noiseKeyHex.take(16))
    }

    override fun onAllCleared() {
        // Leave queued envelopes intact — routing may still become possible later.
    }
}
