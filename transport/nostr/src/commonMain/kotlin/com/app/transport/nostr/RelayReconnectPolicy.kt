package com.app.transport.nostr

import com.app.transport.NostrConstants
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Pure reconnect/backoff policy for [NostrRelayManager] — extracted so the mobile-resilience rules are
 * unit-testable without WebSocket machinery (all timing is returned as numbers, never slept).
 *
 * Behaviour vs the reference iOS `NostrRelayManager` (owner-requested divergence, documented):
 *  - **DNS is transient, not permanent.** iOS marks a DNS/handshake failure permanent immediately
 *    (`reconnectAttempts = max`). On mobile a DNS failure usually means airplane-mode / lift / tunnel,
 *    so here it is retried like any transient error but with a large [dnsMinBackoffMs] floor.
 *  - **Failures decay.** Like iOS `isPermanentlyFailed`, an exhausted relay is revived once its last
 *    disconnect is older than [failureCooldownMs] — but the transport layer has no foreground/activity
 *    hooks to trigger that, so [NostrRelayManager] drives it from a periodic re-probe loop instead.
 */
internal class RelayReconnectPolicy(
    private val initialBackoffMs: Long = NostrConstants.INITIAL_BACKOFF_INTERVAL_MS,
    private val maxBackoffMs: Long = NostrConstants.MAX_BACKOFF_INTERVAL_MS,
    private val multiplier: Double = NostrConstants.BACKOFF_MULTIPLIER,
    private val maxAttempts: Int = NostrConstants.MAX_RECONNECT_ATTEMPTS,
    private val dnsMinBackoffMs: Long = NostrConstants.DNS_MIN_BACKOFF_MS,
    private val failureCooldownMs: Long = NostrConstants.FAILURE_COOLDOWN_MS,
    private val jitterRatio: Double = NostrConstants.BACKOFF_JITTER_RATIO,
    // Uniform value in [0,1); injectable so tests get deterministic backoff (0.5 => no net jitter).
    private val jitter: () -> Double = { Random.nextDouble() },
) {

    sealed interface Decision {
        /** Reconnect after [delayMs]; [attempt] is the new (post-increment) attempt count to store. */
        data class Retry(val delayMs: Long, val attempt: Int) : Decision

        /** Attempts are used up; the relay waits for the background re-probe to revive it. */
        data object Exhausted : Decision
    }

    /**
     * Decide what to do after a failed connection. [attemptsSoFar] is the relay's current (pre-failure)
     * attempt count; [isDns] applies the DNS backoff floor.
     */
    fun onFailure(attemptsSoFar: Int, isDns: Boolean): Decision {
        val attempt = attemptsSoFar + 1
        if (attempt >= maxAttempts) return Decision.Exhausted
        val base = min(initialBackoffMs * multiplier.pow(attempt - 1.0), maxBackoffMs.toDouble())
        val floored = if (isDns) max(base, dnsMinBackoffMs.toDouble()) else base
        val jittered = floored * (1.0 + (jitter() * 2.0 - 1.0) * jitterRatio)
        return Decision.Retry(jittered.toLong(), attempt)
    }

    /**
     * Whether an exhausted relay should get a fresh attempt now. [force] (network-just-returned) skips
     * the cooldown. A null [lastDisconnectedAtMs] is treated as cooled down.
     */
    fun shouldReprobe(
        reconnectAttempts: Int,
        lastDisconnectedAtMs: Long?,
        nowMs: Long,
        force: Boolean = false,
    ): Boolean {
        if (reconnectAttempts < maxAttempts) return false
        if (force) return true
        return lastDisconnectedAtMs?.let { nowMs - it >= failureCooldownMs } ?: true
    }

    fun isDnsError(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return m.contains("hostname could not be found") ||
            m.contains("dns") ||
            m.contains("unable to resolve host") ||
            m.contains("nodename nor servname")
    }
}
