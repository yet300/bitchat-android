package com.app.data.repository

import com.app.domain.model.PacketLogKind
import com.app.transport.debug.DebugMessage
import com.app.transport.debug.DebugPreferenceManager
import com.app.transport.debug.DebugSettingsManager
import com.app.transport.mesh.BleDebugHandle
import com.app.transport.mesh.BluetoothMeshService
import com.app.transport.meshgraph.MeshGraphService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DebugRepositoryImplTest {

    private lateinit var prefs: DebugPreferenceManager
    private lateinit var ble: BleDebugHandle
    private lateinit var mesh: BluetoothMeshService
    private lateinit var telemetry: DebugSettingsManager
    private lateinit var meshGraph: MeshGraphService
    private val messages = MutableStateFlow<List<DebugMessage>>(emptyList())
    private val graph = MutableStateFlow(MeshGraphService.GraphSnapshot(emptyList(), emptyList()))
    private lateinit var repo: DebugRepositoryImpl

    @Before
    fun setUp() {
        prefs = mock()
        ble = mock()
        mesh = mock()
        telemetry = mock()
        meshGraph = mock()
        whenever(mesh.bleDebug).thenReturn(ble)
        whenever(telemetry.debugMessages).thenReturn(messages)
        whenever(meshGraph.graphState).thenReturn(graph)
        repo = DebugRepositoryImpl(prefs, mesh, telemetry, meshGraph)
    }

    @Test
    fun mesh_topology_maps_nodes_and_confirmed_edges() = runTest {
        graph.value = MeshGraphService.GraphSnapshot(
            nodes = listOf(MeshGraphService.GraphNode("aa", "alice"), MeshGraphService.GraphNode("bb", null)),
            edges = listOf(MeshGraphService.GraphEdge("aa", "bb", isConfirmed = true)),
        )
        val topology = repo.observeMeshTopology().first()
        assertEquals(listOf("alice", null), topology.nodes.map { it.nickname })
        assertEquals(1, topology.edges.size)
        assertTrue(topology.edges.single().confirmed)
    }

    @Test
    fun packet_log_maps_message_classes_to_kinds() = runTest {
        messages.value = listOf(
            DebugMessage.SystemMessage("boot"),
            DebugMessage.PacketEvent("pkt"),
            DebugMessage.RelayEvent("relay"),
            DebugMessage.PeerEvent("peer"),
        )
        val entries = repo.observePacketLog().first()
        assertEquals(
            listOf(PacketLogKind.SYSTEM, PacketLogKind.PACKET, PacketLogKind.RELAY, PacketLogKind.PEER),
            entries.map { it.kind },
        )
    }

    @Test
    fun enabling_the_gatt_server_persists_and_starts_the_bearer() {
        repo.setGattServerEnabled(true)
        verify(prefs).setGattServerEnabled(true)
        verify(ble).startServer()
        verify(ble, never()).stopServer()
    }

    @Test
    fun disabling_the_gatt_client_persists_and_stops_the_bearer() {
        repo.setGattClientEnabled(false)
        verify(prefs).setGattClientEnabled(false)
        verify(ble).stopClient()
        verify(ble, never()).startClient()
    }

    @Test
    fun getters_delegate_to_the_preference_manager() {
        whenever(prefs.getSeenPacketCapacity()).thenReturn(750)
        whenever(prefs.getVerboseLogging()).thenReturn(true)
        assertEquals(750, repo.seenPacketCapacity())
        assertTrue(repo.verboseLoggingEnabled())
    }

    @Test
    fun debug_status_survives_a_thrown_mesh_call() {
        whenever(mesh.getDebugStatus()).thenThrow(RuntimeException("boom"))
        assertEquals("unavailable", repo.debugStatus())
    }
}
