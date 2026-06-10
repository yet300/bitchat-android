
package com.bitchat.android.mesh

import com.app.domain.repository.SettingsStore
import com.app.transport.debug.DebugPreferenceManager
import com.app.transport.debug.DebugSettingsManager
import com.app.transport.mesh.PacketRelayManager
import com.app.transport.mesh.PacketRelayManagerDelegate
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
class PacketRelayManagerTest {

    private lateinit var packetRelayManager: PacketRelayManager
    private val delegate: PacketRelayManagerDelegate = mock()

    private val myPeerID = "1111111111111111"
    private val otherPeerID = "2222222222222222"
    private val nextHopPeerID = "3333333333333333"
    private val finalRecipientID = "4444444444444444"

    @Before
    fun setUp() {
        val debugSettingsManager = DebugSettingsManager(DebugPreferenceManager(FakeSettingsStore()))
        packetRelayManager = PacketRelayManager(myPeerID, debugSettingsManager)
        packetRelayManager.delegate = delegate
        whenever(delegate.getNetworkSize()).thenReturn(10)
        whenever(delegate.getBroadcastRecipient()).thenReturn(byteArrayOf(0,0,0,0,0,0,0,0))
    }

    private fun createPacket(route: List<ByteArray>?, recipient: String? = null): BitchatPacket {
        return BitchatPacket(
            type = MessageType.MESSAGE.value,
            senderID = hexStringToPeerBytes(otherPeerID),
            recipientID = recipient?.let { hexStringToPeerBytes(it) },
            timestamp = System.currentTimeMillis().toULong(),
            payload = "hello".toByteArray(),
            ttl = 5u,
            route = route
        )
    }

    @Test
    fun `packet with duplicate hops is dropped`() = runTest {
        val route = listOf(
            hexStringToPeerBytes(nextHopPeerID),
            hexStringToPeerBytes(nextHopPeerID)
        )
        val packet = createPacket(route)
        val routedPacket = RoutedPacket(packet, otherPeerID)

        packetRelayManager.handlePacketRelay(routedPacket)

        verify(delegate, never()).sendToPeer(any(), any())
        verify(delegate, never()).broadcastPacket(any())
    }

    @Test
    fun `valid source-routed packet is relayed to next hop`() = runTest {
        val route = listOf(
            hexStringToPeerBytes(myPeerID),
            hexStringToPeerBytes(nextHopPeerID)
        )
        val packet = createPacket(route, finalRecipientID)
        val routedPacket = RoutedPacket(packet, otherPeerID)
        whenever(delegate.sendToPeer(any(), any())).thenReturn(true)

        packetRelayManager.handlePacketRelay(routedPacket)

        verify(delegate).sendToPeer(org.mockito.kotlin.eq(nextHopPeerID), any())
        verify(delegate, never()).broadcastPacket(any())
    }

    @Test
    fun `last hop does not relay further`() = runTest {
        val route = listOf(
            hexStringToPeerBytes(myPeerID)
        )
        val packet = createPacket(route, finalRecipientID)
        val routedPacket = RoutedPacket(packet, otherPeerID)
        whenever(delegate.sendToPeer(any(), any())).thenReturn(true)

        packetRelayManager.handlePacketRelay(routedPacket)

        verify(delegate).sendToPeer(org.mockito.kotlin.eq(finalRecipientID), any())
        verify(delegate, never()).broadcastPacket(any())
    }
    
    @Test
    fun `packet with empty route is broadcast`() = runTest {
        val packet = createPacket(null)
        val routedPacket = RoutedPacket(packet, otherPeerID)

        packetRelayManager.handlePacketRelay(routedPacket)

        verify(delegate, never()).sendToPeer(any(), any())
        verify(delegate).broadcastPacket(any())
    }

    /** Minimal in-memory [SettingsStore] returning defaults (debug toggles default-on). */
    private class FakeSettingsStore : SettingsStore {
        override fun getString(key: String, defaultValue: String): String = defaultValue
        override fun getStringOrNull(key: String): String? = null
        override fun putString(key: String, value: String) {}
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue
        override fun putBoolean(key: String, value: Boolean) {}
        override fun getInt(key: String, defaultValue: Int): Int = defaultValue
        override fun putInt(key: String, value: Int) {}
        override fun getLong(key: String, defaultValue: Long): Long = defaultValue
        override fun putLong(key: String, value: Long) {}
        override fun getDouble(key: String, defaultValue: Double): Double = defaultValue
        override fun putDouble(key: String, value: Double) {}
        override fun hasKey(key: String): Boolean = false
        override fun remove(key: String) {}
        override fun getStringFlow(key: String, defaultValue: String): Flow<String> = flowOf(defaultValue)
        override fun getStringOrNullFlow(key: String): Flow<String?> = flowOf(null)
    }

    private fun hexStringToPeerBytes(hex: String): ByteArray {
        val result = ByteArray(8)
        var idx = 0
        var out = 0
        while (idx + 1 < hex.length && out < 8) {
            val b = hex.substring(idx, idx + 2).toIntOrNull(16)?.toByte() ?: 0
            result[out++] = b
            idx += 2
        }
        return result
    }
}
