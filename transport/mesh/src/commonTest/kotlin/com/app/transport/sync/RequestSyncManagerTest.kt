package com.app.transport.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** iOS RequestSyncManagerTests parity — pending-request window for RSR solicitation. */
class RequestSyncManagerTest {

    @Test
    fun nonRsrIsNeverValid() {
        var now = 1_000L
        val manager = RequestSyncManager(responseWindowMs = 30_000L, nowMs = { now })
        manager.registerRequest("aaaaaaaaaaaaaaaa")
        assertFalse(manager.isValidResponse("aaaaaaaaaaaaaaaa", isRSR = false))
    }

    @Test
    fun unsolicitedRsrRejected() {
        val manager = RequestSyncManager(responseWindowMs = 30_000L, nowMs = { 1_000L })
        assertFalse(manager.isValidResponse("bbbbbbbbbbbbbbbb", isRSR = true))
    }

    @Test
    fun solicitedRsrAcceptedWithinWindow() {
        var now = 1_000L
        val manager = RequestSyncManager(responseWindowMs = 30_000L, nowMs = { now })
        val peer = "cccccccccccccccc"
        manager.registerRequest(peer)
        now = 20_000L
        assertTrue(manager.isValidResponse(peer, isRSR = true))
    }

    @Test
    fun rsrOutsideWindowRejected() {
        var now = 1_000L
        val manager = RequestSyncManager(responseWindowMs = 30_000L, nowMs = { now })
        val expired = "dddddddddddddddd"
        val fresh = "eeeeeeeeeeeeeeee"
        manager.registerRequest(expired)
        now = 20_000L
        manager.registerRequest(fresh)
        now = 40_000L // expired at 1_000 is 39s old; fresh at 20_000 is 20s old
        assertFalse(manager.isValidResponse(expired, isRSR = true))
        assertTrue(manager.isValidResponse(fresh, isRSR = true))
    }

    @Test
    fun cleanupRemovesExpired() {
        var now = 1_000L
        val manager = RequestSyncManager(responseWindowMs = 30_000L, nowMs = { now })
        manager.registerRequest("ffffffffffffffff")
        assertEquals(1, manager.debugPendingRequestCount())
        now = 50_000L
        manager.cleanup()
        assertEquals(0, manager.debugPendingRequestCount())
        assertFalse(manager.isValidResponse("ffffffffffffffff", isRSR = true))
    }
}
