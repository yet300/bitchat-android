package com.app.transport.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Threshold pins for the Noise intake budgets (iOS NoiseRateLimiter parity):
 * handshakes per-peer 10/min + global 30/min; messages per-peer 100/s + global 500/s;
 * global window checked first; recorded on allow only; per-peer reset.
 */
class NoiseRateLimiterTest {

    private var now = 1_000_000L
    private val limiter = NoiseRateLimiter { now }

    @Test
    fun eleventhHandshakeFromOnePeerIsDropped() {
        repeat(10) { assertTrue("handshake ${it + 1}", limiter.allowHandshake("peerA")) }
        assertFalse(limiter.allowHandshake("peerA"))
        // Another peer still has budget (global 30 not exhausted).
        assertTrue(limiter.allowHandshake("peerB"))
    }

    @Test
    fun handshakeWindowSlidesOpenAfterOneMinute() {
        repeat(10) { assertTrue(limiter.allowHandshake("peerA")) }
        assertFalse(limiter.allowHandshake("peerA"))
        now += NoiseSecurityConstants.HANDSHAKE_WINDOW_MS + 1
        assertTrue(limiter.allowHandshake("peerA"))
    }

    @Test
    fun globalHandshakeBudgetIsCheckedFirst() {
        // 3 peers x 10 = 30 fills the global window; a 4th peer with an empty
        // per-peer budget is still denied.
        for (peer in listOf("a", "b", "c")) repeat(10) { assertTrue(limiter.allowHandshake(peer)) }
        assertFalse(limiter.allowHandshake("freshPeer"))
    }

    @Test
    fun perPeerMessageBudgetIsHundredPerSecond() {
        repeat(100) { assertTrue(limiter.allowMessage("peerA")) }
        assertFalse(limiter.allowMessage("peerA"))
        now += NoiseSecurityConstants.MESSAGE_WINDOW_MS + 1
        assertTrue(limiter.allowMessage("peerA"))
    }

    @Test
    fun globalMessageBudgetIsFiveHundredPerSecond() {
        for (p in 0 until 5) repeat(100) { assertTrue(limiter.allowMessage("peer$p")) }
        assertFalse(limiter.allowMessage("peer99"))
    }

    @Test
    fun resetForgetsOnlyThatPeer() {
        repeat(10) { assertTrue(limiter.allowHandshake("peerA")) }
        repeat(10) { assertTrue(limiter.allowHandshake("peerB")) }
        assertFalse(limiter.allowHandshake("peerA"))
        limiter.reset("peerA")
        // Per-peer budget restored; global window still holds 20 -> allowed.
        assertTrue(limiter.allowHandshake("peerA"))
        assertFalse(limiter.allowHandshake("peerB"))
    }

    @Test
    fun resetAllClearsGlobalWindows() {
        for (peer in listOf("a", "b", "c")) repeat(10) { assertTrue(limiter.allowHandshake(peer)) }
        assertFalse(limiter.allowHandshake("d"))
        limiter.resetAll()
        assertTrue(limiter.allowHandshake("d"))
    }

    @Test
    fun deniedAttemptsAreNotRecorded() {
        repeat(10) { assertTrue(limiter.allowHandshake("peerA")) }
        repeat(50) { assertFalse(limiter.allowHandshake("peerA")) } // hammering
        now += NoiseSecurityConstants.HANDSHAKE_WINDOW_MS + 1
        assertTrue("lockout must not extend past the window", limiter.allowHandshake("peerA"))
    }
}
