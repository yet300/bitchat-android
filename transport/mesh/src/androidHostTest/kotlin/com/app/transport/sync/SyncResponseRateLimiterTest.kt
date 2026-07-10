package com.app.transport.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Threshold pins for the REQUEST_SYNC response limiter (iOS parity: 8 responses / 30 s
 * sliding window, per requester).
 */
class SyncResponseRateLimiterTest {

    @Test
    fun ninthRequestWithinWindowIsDropped() {
        val limiter = SyncResponseRateLimiter()
        var now = 1_000L
        repeat(8) {
            assertTrue("request ${it + 1} must be allowed", limiter.shouldRespond("peerA", now))
            now += 1_000L // 8 requests spread over 8 s, all inside the 30 s window
        }
        assertFalse("9th request within 30s must be dropped", limiter.shouldRespond("peerA", now))
    }

    @Test
    fun windowReopensAfterThirtySeconds() {
        val limiter = SyncResponseRateLimiter()
        val start = 1_000L
        repeat(8) { assertTrue(limiter.shouldRespond("peerA", start)) }
        assertFalse(limiter.shouldRespond("peerA", start + SyncResponseRateLimiter.WINDOW_MILLIS - 1))
        // Once the first 8 fall out of the sliding window, responses flow again.
        assertTrue(limiter.shouldRespond("peerA", start + SyncResponseRateLimiter.WINDOW_MILLIS + 1))
    }

    @Test
    fun limitIsPerRequester() {
        val limiter = SyncResponseRateLimiter()
        val now = 1_000L
        repeat(8) { assertTrue(limiter.shouldRespond("peerA", now)) }
        assertFalse(limiter.shouldRespond("peerA", now))
        assertTrue("another peer has its own budget", limiter.shouldRespond("peerB", now))
    }

    @Test
    fun rejectedRequestIsNotRecorded() {
        val limiter = SyncResponseRateLimiter()
        val start = 1_000L
        repeat(8) { assertTrue(limiter.shouldRespond("peerA", start)) }
        // Hammering while limited must not extend the lockout (iOS records on allow only).
        repeat(100) { assertFalse(limiter.shouldRespond("peerA", start + it * 100L)) }
        assertTrue(limiter.shouldRespond("peerA", start + SyncResponseRateLimiter.WINDOW_MILLIS + 1))
    }
}
