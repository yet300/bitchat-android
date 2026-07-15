package com.app.transport.sync

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Outgoing REQUEST_SYNC solicitation window for validating inbound RSR packets.
 *
 * Port of iOS `RequestSyncManager`: only peers we recently asked for sync may deliver
 * `isRSR` frames, and only within [responseWindowMs] (default 30s).
 */
@OptIn(ExperimentalTime::class)
class RequestSyncManager(
    private val responseWindowMs: Long = DEFAULT_RESPONSE_WINDOW_MS,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    companion object {
        const val DEFAULT_RESPONSE_WINDOW_MS: Long = 30_000L
    }

    private val lock = Lock()
    private val pendingRequests = mutableMapOf<String, Long>()

    /** Register that we are sending a sync request to [peerID]. */
    fun registerRequest(peerID: String) {
        val now = nowMs()
        lock.withLock {
            pendingRequests[peerID] = now
        }
    }

    /**
     * Whether an inbound packet from [peerID] is a valid solicited RSR.
     * Non-RSR always returns false (caller should not invoke for normal traffic).
     */
    fun isValidResponse(peerID: String, isRSR: Boolean): Boolean {
        if (!isRSR) return false
        val now = nowMs()
        return lock.withLock {
            val requestTime = pendingRequests[peerID] ?: return@withLock false
            now - requestTime <= responseWindowMs
        }
    }

    /** Drop expired pending requests (iOS periodic cleanup). */
    fun cleanup() {
        val now = nowMs()
        lock.withLock {
            val iter = pendingRequests.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (now - entry.value > responseWindowMs) {
                    iter.remove()
                }
            }
        }
    }

    /** Test/debug only. */
    internal fun debugPendingRequestCount(): Int = lock.withLock { pendingRequests.size }
}
