package com.app.transport.meshgraph

import com.app.common.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Snapshot publishing is now coalesced (S4): mutations only mark the graph dirty; a timer
 * republishes at most once per PUBLISH_INTERVAL_MS. Tests drive that timer on virtual time via
 * [settle] and assert the rebuild logic is unchanged, plus the new coalescing and node cap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshGraphServiceTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var service: MeshGraphService

    @Before
    fun setUp() {
        service = MeshGraphService(AppDispatchers(default = StandardTestDispatcher(scheduler)))
    }

    /** Fire the publish timer once (interval is 1500 ms). */
    private fun settle() {
        scheduler.advanceTimeBy(2_000)
        scheduler.runCurrent()
    }

    @Test
    fun testUpdateFromAnnouncement_AddsNeighbors() {
        service.updateFromAnnouncement("PeerA", "Alice", listOf("PeerB", "PeerC"), 100UL)
        settle()

        val snapshot = service.graphState.value
        assertTrue(snapshot.nodes.any { it.peerID == "PeerA" })
        assertTrue(snapshot.nodes.any { it.peerID == "PeerB" })
        assertTrue(snapshot.nodes.any { it.peerID == "PeerC" })

        val edgeAB = snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerB") || (it.a == "PeerB" && it.b == "PeerA") }
        assertNotNull(edgeAB)
        assertFalse(edgeAB!!.isConfirmed)
        assertEquals("PeerA", edgeAB.confirmedBy)

        val edgeAC = snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerC") || (it.a == "PeerC" && it.b == "PeerA") }
        assertNotNull(edgeAC)
        assertFalse(edgeAC!!.isConfirmed)
        assertEquals("PeerA", edgeAC.confirmedBy)
    }

    @Test
    fun testUpdateFromAnnouncement_NewerTimestampReplacesNeighbors() {
        service.updateFromAnnouncement("PeerA", "Alice", listOf("PeerB", "PeerC"), 100UL)
        service.updateFromAnnouncement("PeerA", "Alice", listOf("PeerB", "PeerD"), 200UL)
        settle()

        val snapshot = service.graphState.value
        assertNotNull(snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerB") || (it.a == "PeerB" && it.b == "PeerA") })
        assertNotNull(snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerD") || (it.a == "PeerD" && it.b == "PeerA") })
        assertNull(snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerC") || (it.a == "PeerC" && it.b == "PeerA") })
    }

    @Test
    fun testUpdateFromAnnouncement_OlderTimestampIsIgnored() {
        service.updateFromAnnouncement("PeerA", "Alice", listOf("PeerB", "PeerC"), 200UL)
        service.updateFromAnnouncement("PeerA", "Alice", listOf("PeerD"), 100UL)
        settle()

        val snapshot = service.graphState.value
        assertNotNull(snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerB") || (it.a == "PeerB" && it.b == "PeerA") })
        assertNotNull(snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerC") || (it.a == "PeerC" && it.b == "PeerA") })
        assertNull(snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerD") || (it.a == "PeerD" && it.b == "PeerA") })
    }

    @Test
    fun testUpdateFromAnnouncement_NullNeighborsClearsList_TheFix() {
        service.updateFromAnnouncement("PeerA", "Alice", listOf("PeerB", "PeerC"), 100UL)
        service.updateFromAnnouncement("PeerA", "Alice", null, 200UL)
        settle()

        val snapshot = service.graphState.value
        val edgesFromA = snapshot.edges.filter { it.a == "PeerA" || it.b == "PeerA" }
        assertTrue("Edges from PeerA should be empty after null update", edgesFromA.isEmpty())
    }

    @Test
    fun testUpdateFromAnnouncement_NullNeighborsWithOlderTimestampIsIgnored() {
        service.updateFromAnnouncement("PeerA", "Alice", listOf("PeerB", "PeerC"), 200UL)
        service.updateFromAnnouncement("PeerA", "Alice", null, 100UL)
        settle()

        val snapshot = service.graphState.value
        assertFalse(snapshot.edges.isEmpty())
        assertNotNull(snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerB") || (it.a == "PeerB" && it.b == "PeerA") })
    }

    @Test
    fun testUpdateNickname_PublishesSnapshotWithNewNickname() {
        service.updateFromAnnouncement("PeerA", "Alice", listOf("PeerB"), 100UL)
        service.updateNickname("PeerA", "Alicia")
        settle()

        val snapshot = service.graphState.value
        assertEquals("Alicia", snapshot.nodes.first { it.peerID == "PeerA" }.nickname)
        assertNotNull(snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerB") || (it.a == "PeerB" && it.b == "PeerA") })
    }

    @Test
    fun manyUpdatesBetweenTicksCoalesceToASinglePublish() {
        val emissions = mutableListOf<MeshGraphService.GraphSnapshot>()
        val collectScope = CoroutineScope(UnconfinedTestDispatcher(scheduler))
        collectScope.launch { service.graphState.collect { emissions.add(it) } }

        // StateFlow replays its initial (empty) value to a new collector.
        assertEquals(1, emissions.size)

        repeat(30) { service.updateFromAnnouncement("Peer$it", "n$it", listOf("Hub"), (it + 1).toULong()) }
        assertEquals("mutations must not publish inline (per-announce storm eliminated)", 1, emissions.size)

        settle()
        assertEquals("30 updates within one interval coalesce to a single publish", 2, emissions.size)

        collectScope.cancel()
    }

    @Test
    fun graphIsCappedToBoundNodeCount() {
        repeat(600) { service.updateFromAnnouncement("Origin$it", null, listOf("Hub"), (it + 1).toULong()) }
        settle()

        val snapshot = service.graphState.value
        assertTrue("node count must be capped (was ${snapshot.nodes.size})", snapshot.nodes.size <= 500)
    }
}
