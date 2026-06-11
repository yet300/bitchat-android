package com.app.transport.mesh

import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MeshNetworkTest {

    private class FakeBearer(override val id: BearerId) : MeshBearer {
        val incomingFlow = MutableSharedFlow<RoutedPacket>(extraBufferCapacity = 16)
        override val incoming: Flow<RoutedPacket> = incomingFlow
        override val neighbors = MutableStateFlow<Set<PeerLink>>(emptySet())
        override val events: Flow<BearerEvent> = emptyFlow()

        val broadcasts = mutableListOf<RoutedPacket>()
        val unicasts = mutableListOf<Pair<String, RoutedPacket>>()
        var sendToPeerResult = true
        var startResult = true

        override fun start(): Boolean = startResult
        override fun stop() = Unit
        override fun broadcast(packet: RoutedPacket) {
            broadcasts.add(packet)
        }

        override fun sendToPeer(peerID: String, packet: RoutedPacket): Boolean {
            unicasts.add(peerID to packet)
            return sendToPeerResult
        }

        override fun cancelTransfer(transferId: String): Boolean = false
        override fun bindPeer(peerID: String, linkAddress: String) = Unit
    }

    private fun packet(timestamp: ULong, type: UByte = MessageType.MESSAGE.value, ttl: UByte = 7u) = BitchatPacket(
        type = type,
        ttl = ttl,
        senderID = ByteArray(8) { 0x11 },
        payload = "hello".toByteArray(),
        timestamp = timestamp,
    )

    @Test
    fun duplicatePacketOnTwoBearersReachesCollectorOnce() = runTest {
        val ble = FakeBearer(BearerId.BLE)
        val wifi = FakeBearer(BearerId.WIFI_AWARE)
        val network = MeshNetwork(setOf(ble, wifi))

        val received = mutableListOf<RoutedPacket>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            network.incoming.collect { received.add(it) }
        }

        val pkt = packet(timestamp = 1000u)
        ble.incomingFlow.emit(RoutedPacket(pkt, "peerA", "AA:11"))
        wifi.incomingFlow.emit(RoutedPacket(pkt, "peerA", "WIFI-1"))

        assertEquals("cross-bearer duplicate must be dropped", 1, received.size)

        // A different packet still flows through
        ble.incomingFlow.emit(RoutedPacket(packet(timestamp = 2000u), "peerA", "AA:11"))
        assertEquals(2, received.size)

        // Re-sending the first packet is also suppressed (LRU remembers it)
        ble.incomingFlow.emit(RoutedPacket(pkt, "peerA", "AA:11"))
        assertEquals(2, received.size)

        job.cancel()
    }

    @Test
    fun directAnnounceBypassesDedupAfterRelayedCopy() = runTest {
        val ble = FakeBearer(BearerId.BLE)
        val network = MeshNetwork(setOf(ble))

        val received = mutableListOf<RoutedPacket>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            network.incoming.collect { received.add(it) }
        }

        // Relayed announce copy (decremented TTL) wins the race and seeds the LRU…
        val announce = packet(timestamp = 1000u, type = MessageType.ANNOUNCE.value, ttl = 7u)
        val relayedCopy = announce.copy(ttl = 6u)
        ble.incomingFlow.emit(RoutedPacket(relayedCopy, "peerC", "CC:11"))
        assertEquals(1, received.size)

        // …but the direct copy (max TTL) MUST still reach the engine: SecurityManager
        // accepts this duplicate on purpose so bindPeer fires for the direct link.
        ble.incomingFlow.emit(RoutedPacket(announce, "peerA", "AA:11"))
        assertEquals("direct announce must not be deduplicated", 2, received.size)

        // Non-announce duplicates at max TTL are still suppressed
        val msg = packet(timestamp = 2000u, ttl = 7u)
        ble.incomingFlow.emit(RoutedPacket(msg, "peerA", "AA:11"))
        ble.incomingFlow.emit(RoutedPacket(msg, "peerA", "AA:11"))
        assertEquals(3, received.size)

        job.cancel()
    }

    @Test
    fun sendToPeerUsesDirectLinkWhenBearerClaimsPeer() {
        val ble = FakeBearer(BearerId.BLE)
        val wifi = FakeBearer(BearerId.WIFI_AWARE)
        ble.neighbors.value = setOf(PeerLink("peerA", "AA:11", isInbound = false))
        val network = MeshNetwork(setOf(ble, wifi))
        network.startAll()

        val path = network.sendToPeer("peerA", RoutedPacket(packet(1u)))

        assertEquals(SendPath.Direct, path)
        assertEquals(1, ble.unicasts.size)
        assertTrue(ble.broadcasts.isEmpty() && wifi.broadcasts.isEmpty())
    }

    @Test
    fun sendToPeerFloodsAllBearersWhenNoneClaimsPeer() {
        val ble = FakeBearer(BearerId.BLE)
        val wifi = FakeBearer(BearerId.WIFI_AWARE)
        val network = MeshNetwork(setOf(ble, wifi))
        network.startAll()

        val path = network.sendToPeer("unknown", RoutedPacket(packet(1u)))

        assertEquals(SendPath.Flooded, path)
        assertEquals(1, ble.broadcasts.size)
        assertEquals(1, wifi.broadcasts.size)
        assertTrue(ble.unicasts.isEmpty())
    }

    @Test
    fun sendToPeerFloodsWhenDirectSendFails() {
        val ble = FakeBearer(BearerId.BLE)
        ble.neighbors.value = setOf(PeerLink("peerA", "AA:11", isInbound = false))
        ble.sendToPeerResult = false
        val network = MeshNetwork(setOf(ble))
        network.startAll()

        val path = network.sendToPeer("peerA", RoutedPacket(packet(1u)))

        assertEquals(SendPath.Flooded, path)
        assertEquals(1, ble.unicasts.size)
        assertEquals(1, ble.broadcasts.size)
    }

    @Test
    fun sendToPeerReportsNoRouteWithoutBearers() {
        val network = MeshNetwork(emptySet())

        assertEquals(SendPath.NoRoute, network.sendToPeer("peerA", RoutedPacket(packet(1u))))
    }

    /** A registered bearer whose start() failed must not fake a Flooded outcome (audit B6). */
    @Test
    fun unstartedBearersDoNotCountTowardsFlooded() {
        val ble = FakeBearer(BearerId.BLE).apply { startResult = false }
        val wifiStub = FakeBearer(BearerId.WIFI_AWARE).apply { startResult = false }
        val network = MeshNetwork(setOf(ble, wifiStub))
        network.startAll()

        val path = network.sendToPeer("peerA", RoutedPacket(packet(1u)))

        assertEquals(SendPath.NoRoute, path)
        assertTrue(ble.broadcasts.isEmpty() && wifiStub.broadcasts.isEmpty())

        // Once a bearer actually starts, flooding resumes
        ble.startResult = true
        network.startAll()
        assertEquals(SendPath.Flooded, network.sendToPeer("peerA", RoutedPacket(packet(2u))))
        assertEquals(1, ble.broadcasts.size)
        assertTrue("stub never started — must not be broadcast to", wifiStub.broadcasts.isEmpty())
    }
}
