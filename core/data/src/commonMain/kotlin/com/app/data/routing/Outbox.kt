package com.app.data.routing

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import com.app.data.favorites.FavoritesChangeListener
import com.app.data.favorites.FavoritesPersistenceService
import com.app.transport.routing.OutgoingEnvelope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * In-memory outbox for envelopes that could not be routed immediately.
 *
 * Listens for favorites changes so queued Nostr-bound messages can be flushed
 * as soon as a mutual-favorite mapping becomes available.
 * Injected as a graph singleton; [RouteSelector] wires the flush callback on init.
 *
 * enqueue/drain share one lock: a ConcurrentHashMap alone only made the map ops atomic —
 * an enqueue could add into a list a concurrent drain had already removed from the map,
 * silently losing the envelope (audit finding A4).
 */
@SingleIn(AppScope::class)
@Inject
internal class Outbox(
    private val favoritesService: FavoritesPersistenceService,
) : FavoritesChangeListener {

    private val lock = Lock()
    private val queued = HashMap<String, MutableList<OutgoingEnvelope>>()

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
        lock.withLock {
            queued.getOrPut(envelope.peerID) { mutableListOf() }.add(envelope)
        }
    }

    /** Removes and returns all envelopes queued for [peerID]. */
    fun drain(peerID: String): List<OutgoingEnvelope> = lock.withLock {
        queued.remove(peerID) ?: emptyList()
    }

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
