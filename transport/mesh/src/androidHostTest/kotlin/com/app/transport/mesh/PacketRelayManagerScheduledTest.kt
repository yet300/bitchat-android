package com.app.transport.mesh

import com.app.common.AppDispatchers
import com.app.transport.debug.DebugConfigStore
import com.app.transport.debug.DebugPreferenceManager
import com.app.transport.debug.DebugSettingsManager
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.SpecialRecipients
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PacketRelayManagerScheduledTest {
    @Test
    fun `broadcast relay waits for the degree jitter delay`() = runTest {
        val relayed = mutableListOf<BitchatPacket>()
        val manager = manager(testScheduler, degree = 4, relayed = relayed)
        val packet = broadcastPacket()

        manager.handlePacketRelay(RoutedPacket(packet, peerID = "2222222222222222"))
        runCurrent()
        assertEquals(emptyList<BitchatPacket>(), relayed)

        advanceTimeBy(149)
        assertEquals(emptyList<BitchatPacket>(), relayed)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, relayed.size)
        assertEquals(5u.toUByte(), relayed.single().ttl)
    }

    @Test
    fun `duplicate cancels a pending relay before its timer fires in a dense enough mesh`() = runTest {
        val relayed = mutableListOf<BitchatPacket>()
        val manager = manager(testScheduler, degree = 3, relayed = relayed)
        val routed = RoutedPacket(broadcastPacket(), peerID = "2222222222222222")

        manager.handlePacketRelay(routed)
        manager.cancelScheduledRelayForDuplicate(routed.packet)
        advanceTimeBy(150)
        runCurrent()

        assertEquals(emptyList<BitchatPacket>(), relayed)
    }

    private fun manager(
        scheduler: TestCoroutineScheduler,
        degree: Int,
        relayed: MutableList<BitchatPacket>,
    ): PacketRelayManager {
        val dispatcher = StandardTestDispatcher(scheduler)
        return PacketRelayManager(
            myPeerID = "1111111111111111",
            debugSettingsManager = DebugSettingsManager(DebugPreferenceManager(FakeDebugConfigStore())),
            dispatchers = AppDispatchers(default = dispatcher, io = dispatcher),
            jitter = { range -> range.last },
        ).also { manager ->
            manager.delegate = object : PacketRelayManagerDelegate {
                override fun getNetworkSize() = degree
                override fun getLocalDegree() = degree
                override fun getBroadcastRecipient() = SpecialRecipients.BROADCAST
                override fun broadcastPacket(routed: RoutedPacket) { relayed += routed.packet }
                override fun sendToPeer(peerID: String, routed: RoutedPacket) = false
            }
        }
    }

    private fun broadcastPacket() = BitchatPacket(
        type = MessageType.MESSAGE.value,
        senderID = ByteArray(8) { 0x44 },
        recipientID = SpecialRecipients.BROADCAST,
        timestamp = 42u,
        payload = byteArrayOf(7, 8),
        ttl = 6u,
    )

    private class FakeDebugConfigStore : DebugConfigStore {
        override fun getVerboseLogging(default: Boolean) = default
        override fun setVerboseLogging(value: Boolean) = Unit
        override fun getGattServerEnabled(default: Boolean) = default
        override fun setGattServerEnabled(value: Boolean) = Unit
        override fun getGattClientEnabled(default: Boolean) = default
        override fun setGattClientEnabled(value: Boolean) = Unit
        override fun getPacketRelayEnabled(default: Boolean) = default
        override fun setPacketRelayEnabled(value: Boolean) = Unit
        override fun getMaxConnectionsOverall(default: Int) = default
        override fun setMaxConnectionsOverall(value: Int) = Unit
        override fun getMaxConnectionsServer(default: Int) = default
        override fun setMaxConnectionsServer(value: Int) = Unit
        override fun getMaxConnectionsClient(default: Int) = default
        override fun setMaxConnectionsClient(value: Int) = Unit
        override fun getSeenPacketCapacity(default: Int) = default
        override fun setSeenPacketCapacity(value: Int) = Unit
        override fun getGcsMaxFilterBytes(default: Int) = default
        override fun setGcsMaxFilterBytes(value: Int) = Unit
        override fun getGcsFprPercent(default: Double) = default
        override fun setGcsFprPercent(value: Double) = Unit
    }
}
