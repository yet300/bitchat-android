@file:OptIn(ExperimentalTime::class)

package com.app.transport.mesh

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Noise DoS budgets — mirror of the reference iOS `Noise/NoiseSecurityConstants.swift`
 * rate-limiting section.
 */
internal object NoiseSecurityConstants {
    const val MAX_HANDSHAKES_PER_MINUTE = 10
    const val MAX_GLOBAL_HANDSHAKES_PER_MINUTE = 30
    const val MAX_MESSAGES_PER_SECOND = 100
    const val MAX_GLOBAL_MESSAGES_PER_SECOND = 500

    const val HANDSHAKE_WINDOW_MS = 60_000L
    const val MESSAGE_WINDOW_MS = 1_000L
}

/**
 * Sliding-window rate limiter for the Noise intake — port of the reference iOS
 * `Noise/NoiseRateLimiter.swift`. Per-peer AND global windows; the global budget is
 * checked FIRST, then per-peer; a hit is recorded only when both allow (iOS order).
 * Bounds handshake-flood CPU (each handshake costs DH work) and decrypt-flood CPU.
 */
internal class NoiseRateLimiter(
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = Lock()
    private val handshakeTimestamps = HashMap<String, MutableList<Long>>()
    private val messageTimestamps = HashMap<String, MutableList<Long>>()
    private val globalHandshakeTimestamps = mutableListOf<Long>()
    private val globalMessageTimestamps = mutableListOf<Long>()

    fun allowHandshake(peerID: String): Boolean = lock.withLock {
        allow(
            peerID = peerID,
            perPeer = handshakeTimestamps,
            global = globalHandshakeTimestamps,
            windowMillis = NoiseSecurityConstants.HANDSHAKE_WINDOW_MS,
            maxPerPeer = NoiseSecurityConstants.MAX_HANDSHAKES_PER_MINUTE,
            maxGlobal = NoiseSecurityConstants.MAX_GLOBAL_HANDSHAKES_PER_MINUTE,
        )
    }

    fun allowMessage(peerID: String): Boolean = lock.withLock {
        allow(
            peerID = peerID,
            perPeer = messageTimestamps,
            global = globalMessageTimestamps,
            windowMillis = NoiseSecurityConstants.MESSAGE_WINDOW_MS,
            maxPerPeer = NoiseSecurityConstants.MAX_MESSAGES_PER_SECOND,
            maxGlobal = NoiseSecurityConstants.MAX_GLOBAL_MESSAGES_PER_SECOND,
        )
    }

    /** Forget a peer's budgets on session reset (iOS reset(for:)). */
    fun reset(peerID: String): Unit = lock.withLock {
        handshakeTimestamps.remove(peerID)
        messageTimestamps.remove(peerID)
    }

    /** Panic wipe (iOS resetAll). */
    fun resetAll(): Unit = lock.withLock {
        handshakeTimestamps.clear()
        messageTimestamps.clear()
        globalHandshakeTimestamps.clear()
        globalMessageTimestamps.clear()
    }

    // Must be called under [lock].
    private fun allow(
        peerID: String,
        perPeer: HashMap<String, MutableList<Long>>,
        global: MutableList<Long>,
        windowMillis: Long,
        maxPerPeer: Int,
        maxGlobal: Int,
    ): Boolean {
        val now = nowMillis()
        val cutoff = now - windowMillis // iOS keeps entries strictly newer than the cutoff

        // Global budget first (iOS order).
        global.removeAll { it <= cutoff }
        if (global.size >= maxGlobal) return false

        val timestamps = perPeer.getOrPut(peerID) { mutableListOf() }
        timestamps.removeAll { it <= cutoff }
        if (timestamps.size >= maxPerPeer) return false

        timestamps.add(now)
        global.add(now)
        return true
    }
}
