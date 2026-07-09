package com.app.transport.mesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Host tests for the commonMain [BleConnectionScheduler], mirroring the reference
 * BLEConnectionSchedulerTests: weak-signal queueing, rate-limit retry, RSSI-scored selection, the
 * central-link cap, and connect back-off after a timeout.
 */
class BleConnectionSchedulerTest {

    private fun scheduler(config: BleRadioConfig = BleRadioConfig()) = BleConnectionScheduler(config)

    private fun candidate(id: String, rssi: Int, nowMs: Long, connectable: Boolean = true) =
        BleConnectionScheduler.Candidate(id, rssi, connectable, nowMs)

    @Test
    fun discoveryQueuesWeakSignalCandidates() {
        // Threshold -90 by default; a -95 candidate is below the gate -> queued, not connected.
        val s = scheduler()
        val now = 1_000_000L
        val decision = s.handleDiscovery(
            candidate("p1", rssi = -95, nowMs = now),
            connectedOrConnectingCount = 0,
            existingState = null,
            peripheralState = BleConnectionScheduler.PeripheralConnectionState.DISCONNECTED,
            nowMs = now,
        )
        assertEquals(BleConnectionScheduler.DiscoveryDecision.Queued, decision)
        assertEquals(1, s.candidateCount)
    }

    @Test
    fun strongSignalConnectsImmediately() {
        val s = scheduler()
        val now = 1_000_000L
        val decision = s.handleDiscovery(
            candidate("p1", rssi = -50, nowMs = now),
            connectedOrConnectingCount = 0,
            existingState = null,
            peripheralState = BleConnectionScheduler.PeripheralConnectionState.DISCONNECTED,
            nowMs = now,
        )
        assertEquals(BleConnectionScheduler.DiscoveryDecision.ConnectNow, decision)
    }

    @Test
    fun discoveryQueuesAndSchedulesRetryWhenRateLimited() {
        val s = scheduler(BleRadioConfig(connectRateLimitMs = 1_000L))
        val now = 1_000_000L
        s.recordConnectionAttempt(now)
        val decision = s.handleDiscovery(
            candidate("p1", rssi = -50, nowMs = now),
            connectedOrConnectingCount = 0,
            existingState = null,
            peripheralState = BleConnectionScheduler.PeripheralConnectionState.DISCONNECTED,
            nowMs = now + 250,
        )
        assertTrue(decision is BleConnectionScheduler.DiscoveryDecision.ScheduleRetry)
        assertTrue((decision as BleConnectionScheduler.DiscoveryDecision.ScheduleRetry).afterMs > 0)
        assertEquals(1, s.candidateCount)
    }

    @Test
    fun linkCapQueuesInsteadOfConnecting() {
        val s = scheduler(BleRadioConfig(maxCentralLinks = 6))
        val now = 1_000_000L
        val decision = s.handleDiscovery(
            candidate("p1", rssi = -40, nowMs = now),
            connectedOrConnectingCount = 6, // at the cap
            existingState = null,
            peripheralState = BleConnectionScheduler.PeripheralConnectionState.DISCONNECTED,
            nowMs = now,
        )
        assertEquals(BleConnectionScheduler.DiscoveryDecision.Queued, decision)
    }

    @Test
    fun nextCandidateSelectsBestScoredCandidate() {
        val s = scheduler()
        val now = 1_000_000L
        s.enqueue(candidate("weak", rssi = -88, nowMs = now))
        s.enqueue(candidate("strong", rssi = -45, nowMs = now))
        val decision = s.nextCandidate(
            connectedOrConnectingCount = 0,
            isAlreadyConnectingOrConnected = { false },
            nowMs = now,
        )
        assertTrue(decision is BleConnectionScheduler.QueueDecision.Connect)
        assertEquals("strong", (decision as BleConnectionScheduler.QueueDecision.Connect).candidate.peripheralID)
    }

    @Test
    fun nextCandidateHonorsLinkCap() {
        val s = scheduler(BleRadioConfig(maxCentralLinks = 6))
        s.enqueue(candidate("p1", rssi = -40, nowMs = 1_000_000L))
        val decision = s.nextCandidate(
            connectedOrConnectingCount = 6,
            isAlreadyConnectingOrConnected = { false },
            nowMs = 1_000_000L,
        )
        assertEquals(BleConnectionScheduler.QueueDecision.None, decision)
    }

    @Test
    fun timeoutTriggersWeakLinkCooldownRetry() {
        val s = scheduler()
        val now = 1_000_000L
        s.recordConnectionTimeout("p1", now)
        // A weak candidate (<= cutoff -90) within the cooldown window is retried later, not connected.
        s.enqueue(candidate("p1", rssi = -92, nowMs = now))
        val decision = s.nextCandidate(
            connectedOrConnectingCount = 0,
            isAlreadyConnectingOrConnected = { false },
            nowMs = now + 1_000,
        )
        assertTrue(decision is BleConnectionScheduler.QueueDecision.RetryAfter)
    }

    @Test
    fun rssiThresholdRelaxesWhenIsolatedAndTightensWhenSaturated() {
        val s = scheduler()
        val now = 1_000_000L
        // Isolated: base relaxed threshold.
        assertEquals(-95, s.updateRssiThreshold(connectedCount = 0, connectedOrConnectingLinkCount = 0, nowMs = now))
        // Isolated long enough: further relaxed.
        assertEquals(-100, s.updateRssiThreshold(connectedCount = 0, connectedOrConnectingLinkCount = 0, nowMs = now + 31_000))
        // Saturated links: tighten to the connected threshold.
        assertEquals(-85, s.updateRssiThreshold(connectedCount = 6, connectedOrConnectingLinkCount = 6, nowMs = now + 40_000))
    }
}
