@file:OptIn(ExperimentalTime::class)

package com.app.transport.mesh

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Per-sender + per-content token buckets for public message intake — port of the
 * reference iOS `ViewModels/MessageRateLimiter.swift` (params from TransportConfig:
 * sender 5 @ 1.0/s, content 3 @ 0.5/s). BOTH buckets must pass. Validated PoW at or
 * above [rateLimitBypassBits] (iOS NostrPoW.rateLimitBypassBits = 8) skips the
 * per-sender bucket only — each such message paid for itself with work — while the
 * per-content flood bucket still applies. Mesh intake passes powBits = 0.
 */
internal class MessageRateLimiter(
    private val senderCapacity: Double = SENDER_CAPACITY,
    private val senderRefillPerSec: Double = SENDER_REFILL_PER_SEC,
    private val contentCapacity: Double = CONTENT_CAPACITY,
    private val contentRefillPerSec: Double = CONTENT_REFILL_PER_SEC,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    companion object {
        // iOS TransportConfig.uiSenderRateBucketCapacity / RefillPerSec and content pair.
        const val SENDER_CAPACITY = 5.0
        const val SENDER_REFILL_PER_SEC = 1.0
        const val CONTENT_CAPACITY = 3.0
        const val CONTENT_REFILL_PER_SEC = 0.5

        /** iOS NostrPoW.rateLimitBypassBits. */
        const val RATE_LIMIT_BYPASS_BITS = 8

        // Anti-DoS bound absent on iOS: senderKey is attacker-controlled (spoofed
        // sender IDs), so the bucket maps must not grow without limit. LRU eviction:
        // a evicted well-behaved key just gets a fresh full bucket next time.
        private const val MAX_TRACKED_KEYS = 4096
    }

    private class TokenBucket(
        val capacity: Double,
        var tokens: Double,
        val refillPerSec: Double,
        var lastRefillMs: Long,
    ) {
        fun allow(nowMs: Long, cost: Double = 1.0): Boolean {
            val dtSec = (nowMs - lastRefillMs) / 1000.0
            if (dtSec > 0) {
                tokens = min(capacity, tokens + dtSec * refillPerSec)
                lastRefillMs = nowMs
            }
            if (tokens >= cost) {
                tokens -= cost
                return true
            }
            return false
        }
    }

    private val lock = Lock()
    private val senderBuckets = LinkedHashMap<String, TokenBucket>()
    private val contentBuckets = LinkedHashMap<String, TokenBucket>()

    /**
     * True when the message may be accepted. Evaluates BOTH buckets (iOS parity:
     * a denied message still consumes from the other bucket).
     */
    fun allow(senderKey: String, contentKey: String, powBits: Int = 0): Boolean = lock.withLock {
        val now = nowMillis()
        val senderAllowed = if (powBits >= RATE_LIMIT_BYPASS_BITS) {
            true
        } else {
            bucket(senderBuckets, senderKey, senderCapacity, senderRefillPerSec, now).allow(now)
        }
        val contentAllowed =
            bucket(contentBuckets, contentKey, contentCapacity, contentRefillPerSec, now).allow(now)
        senderAllowed && contentAllowed
    }

    // Must be called under [lock].
    private fun bucket(
        map: LinkedHashMap<String, TokenBucket>,
        key: String,
        capacity: Double,
        refillPerSec: Double,
        nowMs: Long,
    ): TokenBucket {
        val existing = map.remove(key) // re-insert for LRU recency
        val b = existing ?: TokenBucket(capacity, capacity, refillPerSec, nowMs)
        map[key] = b
        while (map.size > MAX_TRACKED_KEYS) {
            val it = map.entries.iterator()
            if (it.hasNext()) { it.next(); it.remove() } else break
        }
        return b
    }
}
