package com.app.transport.mesh

import com.app.transport.NoOpMeshTelemetry
import com.app.transport.model.RoutedPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wiring test: [BleBearer.bindPeer] retires redundant central-role links via
 * [BleRedundantLinkPolicy] + [BearerTransport.disconnectAddress].
 */
class BleBearerRedundantLinkTest {

    private class FakeTransport : BearerTransport {
        override var delegate: BearerTransportDelegate? = null
        override val addressPeerMap: MutableMap<String, String> = mutableMapOf()
        var clientLinks: MutableList<BleClientLinkSnapshot> = mutableListOf()
        val disconnected = mutableListOf<String>()

        override fun startServices(): Boolean = true
        override fun stopServices() {}
        override fun broadcastPacket(routed: RoutedPacket) {}
        override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean = true
        override fun cancelTransfer(transferId: String): Boolean = true
        override fun isClientConnection(address: String): Boolean? =
            if (clientLinks.any { it.address == address }) true else false

        override fun setNicknameResolver(resolver: (String) -> String?) {}
        override fun setMeshServiceActive(active: Boolean) {}
        override fun setAppIsActive(active: Boolean) {}
        override fun startServer() {}
        override fun stopServer() {}
        override fun startClient() {}
        override fun stopClient() {}
        override fun getConnectedDeviceEntries(): List<Triple<String, Boolean, Int?>> = emptyList()
        override fun getLocalAdapterAddress(): String? = null
        override fun connectToAddress(address: String): Boolean = true
        override fun disconnectAddress(address: String) {
            disconnected += address
            clientLinks.removeAll { it.address == address }
            addressPeerMap.remove(address)
        }
        override fun getDebugInfo(): String = ""
        override fun clientLinkSnapshots(): List<BleClientLinkSnapshot> = clientLinks.toList()
    }

    private fun bearer(
        transport: FakeTransport,
        cooldownMs: Long = 60_000L,
        clock: () -> Long = { 1_000_000L },
    ): BleBearer {
        return BleBearer(
            myPeerID = "1111111111111111",
            debugSettingsManager = NoOpMeshTelemetry,
            connectionManagerFactory = { transport },
            radioConfig = BleRadioConfig(linkRebindCooldownMs = cooldownMs),
            nowMs = clock,
        )
    }

    @Test
    fun bindPeer_retiresDuplicateClientLink_keepingIngress() {
        val transport = FakeTransport()
        transport.clientLinks += listOf(
            BleClientLinkSnapshot("link-old", peerID = "peerA", isConnected = true, hasCharacteristic = true),
            BleClientLinkSnapshot("link-new", peerID = null, isConnected = true, hasCharacteristic = true),
        )
        transport.addressPeerMap["link-old"] = "peerA"

        val b = bearer(transport)
        b.bindPeer("peerA", "link-new")

        assertEquals("peerA", transport.addressPeerMap["link-new"])
        assertFalse(transport.addressPeerMap.containsKey("link-old"))
        assertEquals(listOf("link-old"), transport.disconnected)
        assertTrue(b.neighbors.value.none { it.deviceAddress == "link-old" })
        assertTrue(b.neighbors.value.any { it.deviceAddress == "link-new" && it.peerID == "peerA" })
    }

    @Test
    fun bindPeer_cooldownSuppressesSecondRetirement() {
        val transport = FakeTransport()
        var now = 1_000_000L
        val b = bearer(transport, cooldownMs = 60_000L, clock = { now })

        transport.clientLinks += listOf(
            BleClientLinkSnapshot("a", peerID = "peerA", isConnected = true, hasCharacteristic = true),
            BleClientLinkSnapshot("b", peerID = "peerA", isConnected = true, hasCharacteristic = true),
        )
        b.bindPeer("peerA", "b")
        assertEquals(1, transport.disconnected.size)

        // Re-introduce a third duplicate within cooldown — must not retire again.
        transport.disconnected.clear()
        transport.clientLinks += BleClientLinkSnapshot("c", peerID = "peerA", isConnected = true, hasCharacteristic = true)
        transport.addressPeerMap["c"] = "peerA"
        b.bindPeer("peerA", "c")
        assertTrue(transport.disconnected.isEmpty())

        // After cooldown, retirement runs again.
        now += 60_001L
        transport.clientLinks = mutableListOf(
            BleClientLinkSnapshot("b", peerID = "peerA", isConnected = true, hasCharacteristic = true),
            BleClientLinkSnapshot("c", peerID = "peerA", isConnected = true, hasCharacteristic = true),
            BleClientLinkSnapshot("d", peerID = "peerA", isConnected = true, hasCharacteristic = true),
        )
        b.bindPeer("peerA", "d")
        assertTrue(transport.disconnected.isNotEmpty())
        assertEquals("peerA", transport.addressPeerMap["d"])
    }

    @Test
    fun singleClientLink_noDisconnect() {
        val transport = FakeTransport()
        transport.clientLinks += BleClientLinkSnapshot("only", peerID = null, isConnected = true, hasCharacteristic = true)
        val b = bearer(transport)
        b.bindPeer("peerA", "only")
        assertTrue(transport.disconnected.isEmpty())
        assertEquals("peerA", transport.addressPeerMap["only"])
    }
}
