package com.app.transport.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayReconnectPolicyTest {

    // jitter=0.5 => factor 1 + (0.5*2-1)*ratio = 1.0, so backoff is exact and assertable.
    private fun policy() = RelayReconnectPolicy(jitter = { 0.5 })

    @Test
    fun nonDnsBackoffGrowsExponentiallyAndCaps() {
        val p = policy()
        assertEquals(RelayReconnectPolicy.Decision.Retry(1_000, 1), p.onFailure(0, isDns = false))
        assertEquals(RelayReconnectPolicy.Decision.Retry(2_000, 2), p.onFailure(1, isDns = false))
        assertEquals(RelayReconnectPolicy.Decision.Retry(4_000, 3), p.onFailure(2, isDns = false))
        // attempt 9 => 1000 * 2^8 = 256_000, still under the 300_000 cap.
        assertEquals(RelayReconnectPolicy.Decision.Retry(256_000, 9), p.onFailure(8, isDns = false))
    }

    @Test
    fun attemptsAreCappedAtMaxBackoff() {
        // A tiny max attempts so we can reach a high exponent without hitting Exhausted first.
        val p = RelayReconnectPolicy(maxAttempts = 100, jitter = { 0.5 })
        val decision = p.onFailure(attemptsSoFar = 20, isDns = false) as RelayReconnectPolicy.Decision.Retry
        assertEquals("must not exceed the 300s cap", 300_000L, decision.delayMs)
    }

    @Test
    fun dnsFailureRetriesWithLargeFloorInsteadOfGivingUp() {
        val p = policy()
        val decision = p.onFailure(attemptsSoFar = 0, isDns = true) as RelayReconnectPolicy.Decision.Retry
        // Base backoff would be 1s; the DNS floor lifts it to 60s so we keep retrying, not abandon.
        assertEquals(60_000L, decision.delayMs)
        assertEquals(1, decision.attempt)
    }

    @Test
    fun reachingMaxAttemptsIsExhausted() {
        val p = policy()
        assertEquals(RelayReconnectPolicy.Decision.Exhausted, p.onFailure(attemptsSoFar = 9, isDns = false))
        assertEquals(RelayReconnectPolicy.Decision.Exhausted, p.onFailure(attemptsSoFar = 9, isDns = true))
    }

    @Test
    fun exhaustedRelayIsReprobedOnlyAfterCooldownDecays() {
        val p = policy()
        val now = 10_000_000L
        // Not exhausted yet -> never re-probed.
        assertFalse(p.shouldReprobe(reconnectAttempts = 3, lastDisconnectedAtMs = now - 999_999, nowMs = now))
        // Exhausted but still within cooldown (600s) -> wait.
        assertFalse(p.shouldReprobe(reconnectAttempts = 10, lastDisconnectedAtMs = now - 100_000, nowMs = now))
        // Exhausted and cooled down -> revive.
        assertTrue(p.shouldReprobe(reconnectAttempts = 10, lastDisconnectedAtMs = now - 700_000, nowMs = now))
        // Unknown last-disconnect -> treat as cooled down.
        assertTrue(p.shouldReprobe(reconnectAttempts = 10, lastDisconnectedAtMs = null, nowMs = now))
        // force (network returned) bypasses the cooldown but still requires exhaustion.
        assertTrue(p.shouldReprobe(reconnectAttempts = 10, lastDisconnectedAtMs = now - 1, nowMs = now, force = true))
        assertFalse(p.shouldReprobe(reconnectAttempts = 5, lastDisconnectedAtMs = null, nowMs = now, force = true))
    }

    @Test
    fun classifiesDnsErrorsFromMessages() {
        val p = policy()
        assertTrue(p.isDnsError("The hostname could not be found"))
        assertTrue(p.isDnsError("Unable to resolve host \"relay.damus.io\""))
        assertTrue(p.isDnsError("nodename nor servname provided"))
        assertFalse(p.isDnsError("Connection reset by peer"))
        assertFalse(p.isDnsError(null))
    }
}
