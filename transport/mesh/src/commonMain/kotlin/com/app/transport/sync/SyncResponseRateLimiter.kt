package com.app.transport.sync

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock

/**
 * Sliding-window limiter for REQUEST_SYNC responses (port of the reference iOS
 * `Sync/SyncResponseRateLimiter.swift`).
 *
 * A single sync response can replay the entire gossip store, so a peer that
 * requests in a tight loop must not be able to drain the airtime and battery
 * of everyone in radio range. Legitimate peers send at most a few requests
 * per maintenance tick (one per type schedule, plus the initial sync).
 */
internal class SyncResponseRateLimiter(
    maxResponses: Int = MAX_RESPONSES,
    windowMillis: Long = WINDOW_MILLIS,
) {
    companion object {
        // iOS Config.responseRateLimitMaxResponses / window (8 responses / 30 s).
        const val MAX_RESPONSES: Int = 8
        const val WINDOW_MILLIS: Long = 30_000L
    }

    private val maxResponses = maxResponses.coerceAtLeast(1)
    private val windowMillis = windowMillis.coerceAtLeast(0L)

    private val lock = Lock()
    private val history = HashMap<String, MutableList<Long>>()

    /**
     * Returns true (and records the response) if [peerID] is under its response
     * budget for the current window.
     */
    fun shouldRespond(peerID: String, nowMillis: Long): Boolean = lock.withLock {
        val cutoff = nowMillis - windowMillis
        val recent = (history[peerID] ?: mutableListOf()).apply { removeAll { it < cutoff } }
        if (recent.size >= maxResponses) {
            history[peerID] = recent
            return@withLock false
        }
        recent.add(nowMillis)
        history[peerID] = recent
        true
    }

    /** Drops history outside the window so departed peers don't accumulate. */
    fun prune(nowMillis: Long): Unit = lock.withLock {
        val cutoff = nowMillis - windowMillis
        val it = history.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            entry.value.removeAll { ts -> ts < cutoff }
            if (entry.value.isEmpty()) it.remove()
        }
    }
}
