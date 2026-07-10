package com.app.transport.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Threshold pins for the public message intake buckets (iOS MessageRateLimiter parity):
 * per-sender capacity 5 refill 1.0/s, per-content capacity 3 refill 0.5/s, both must
 * pass; validated PoW >= 8 bits bypasses only the sender bucket.
 */
class MessageRateLimiterTest {

    private var now = 1_000_000L
    private val limiter = MessageRateLimiter(nowMillis = { now })

    @Test
    fun sixthBurstMessageFromOneSenderIsDropped() {
        repeat(5) { i -> assertTrue("message ${i + 1}", limiter.allow("sender", "content-$i")) }
        assertFalse(limiter.allow("sender", "content-5"))
        // A different sender still passes (its own bucket).
        assertTrue(limiter.allow("other", "content-6"))
    }

    @Test
    fun senderBucketRefillsAtOnePerSecond() {
        repeat(5) { i -> assertTrue(limiter.allow("sender", "c$i")) }
        assertFalse(limiter.allow("sender", "c5"))
        now += 1_000 // +1 token
        assertTrue(limiter.allow("sender", "c6"))
        assertFalse(limiter.allow("sender", "c7"))
    }

    @Test
    fun contentBucketLimitsIdenticalContentAcrossSenders() {
        repeat(3) { i -> assertTrue("copy ${i + 1}", limiter.allow("sender$i", "same-content")) }
        // 4th copy of the same content, from a fresh sender: content bucket empty.
        assertFalse(limiter.allow("sender99", "same-content"))
        // Content refill is 0.5/s -> one more copy after 2 s.
        now += 2_000
        assertTrue(limiter.allow("sender100", "same-content"))
    }

    @Test
    fun powBypassSkipsSenderBucketButNotContentBucket() {
        repeat(5) { i -> assertTrue(limiter.allow("sender", "c$i")) }
        assertFalse(limiter.allow("sender", "c5")) // sender bucket empty
        // PoW at the bypass threshold: sender bucket skipped, distinct content passes.
        assertTrue(limiter.allow("sender", "c6", powBits = MessageRateLimiter.RATE_LIMIT_BYPASS_BITS))
        // But the content flood bucket still applies even with PoW.
        repeat(2) { assertTrue(limiter.allow("sender", "flood", powBits = 8)) }
        assertTrue(limiter.allow("sender2", "flood", powBits = 8))
        assertFalse(limiter.allow("sender3", "flood", powBits = 8))
    }

    @Test
    fun meshDefaultPowBitsZeroNeverBypasses() {
        repeat(5) { i -> assertTrue(limiter.allow("sender", "c$i", powBits = 0)) }
        assertFalse(limiter.allow("sender", "c5", powBits = 0))
    }
}
