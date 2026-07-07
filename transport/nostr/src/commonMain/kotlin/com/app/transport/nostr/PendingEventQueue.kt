package com.app.transport.nostr

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock

/**
 * Queue policy for events that could not be delivered immediately: each entry tracks the
 * relay URLs that still need to receive the event. Connected relays are never enqueued —
 * the caller sends to them right away (see [submit]); a relay's backlog is drained when
 * it (re)connects (see [drainFor]).
 *
 * Bounded: when [maxEntries] is exceeded the oldest entries are dropped and returned to
 * the caller for logging.
 *
 * Thread-safe via an internal lock. Extracted from NostrRelayManager so the policy is
 * unit-testable without WebSocket machinery.
 */
internal class PendingEventQueue<E>(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    companion object {
        const val DEFAULT_MAX_ENTRIES = 256
    }

    /** [sendNow] — relays with a live connection; [dropped] — oldest events evicted by the cap. */
    data class SubmitResult<E>(val sendNow: List<String>, val dropped: List<E>)

    private val lock = Lock()
    private val entries = mutableListOf<Pair<E, MutableList<String>>>()

    /**
     * Partition [targetRelays] by [isConnected]: connected relays are returned in
     * [SubmitResult.sendNow] for immediate delivery and are NOT queued; the rest are
     * queued until their relay reconnects.
     */
    fun submit(event: E, targetRelays: List<String>, isConnected: (String) -> Boolean): SubmitResult<E> {
        // Caller-supplied callback runs before the lock is taken — delegate code must
        // never execute under it.
        val (connected, pending) = targetRelays.partition(isConnected)
        if (pending.isEmpty()) return SubmitResult(connected, emptyList())
        val dropped = mutableListOf<E>()
        lock.withLock {
            entries.add(event to pending.toMutableList())
            while (entries.size > maxEntries) {
                dropped.add(entries.removeAt(0).first)
            }
        }
        return SubmitResult(connected, dropped)
    }

    /**
     * Remove [relayUrl] from every entry's pending list and return the events that were
     * waiting for it (in submission order). Entries with no remaining relays are removed.
     */
    fun drainFor(relayUrl: String): List<E> {
        lock.withLock {
            val toSend = mutableListOf<E>()
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val (event, pendingRelays) = iterator.next()
                if (pendingRelays.remove(relayUrl)) {
                    toSend.add(event)
                    if (pendingRelays.isEmpty()) {
                        iterator.remove()
                    }
                }
            }
            return toSend
        }
    }

    fun clear() = lock.withLock { entries.clear() }

    fun size(): Int = lock.withLock { entries.size }

    /** Pending relay URLs of the entry holding [event], or null if not queued. Test hook. */
    fun pendingRelaysOf(event: E): List<String>? = lock.withLock {
        entries.firstOrNull { it.first == event }?.second?.toList()
    }
}
