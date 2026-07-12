package com.app.transport.mesh

import com.app.transport.MeshTelemetry
import com.app.transport.NoOpMeshTelemetry
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * S1 fix: the bearer ingress buffer must not silently discard inbound frames. Under a stalled
 * consumer the bounded channel drops the *oldest* frames (usually already relayed by neighbors),
 * keeps the newest (which may be a Noise handshake response), and counts every drop in telemetry.
 */
class BleBearerIngressDropTest {

    /** Real ingress capacity of [BleBearer] (private const INCOMING_BUFFER_CAPACITY). */
    private val capacity = 256

    private class CountingTelemetry : MeshTelemetry by NoOpMeshTelemetry {
        val dropped = AtomicInteger(0)
        override fun onIncomingDropped(bearerId: BearerId) {
            dropped.incrementAndGet()
        }
    }

    private class FakeBearerTransport : BearerTransport {
        val appActivity = mutableListOf<Boolean>()
        override var delegate: BearerTransportDelegate? = null
        override val addressPeerMap: MutableMap<String, String> = mutableMapOf()
        override fun startServices(): Boolean = true
        override fun stopServices() {}
        override fun broadcastPacket(routed: RoutedPacket) {}
        override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean = true
        override fun cancelTransfer(transferId: String): Boolean = true
        override fun isClientConnection(address: String): Boolean? = null
        override fun setNicknameResolver(resolver: (String) -> String?) {}
        override fun setMeshServiceActive(active: Boolean) {}
        override fun setAppIsActive(active: Boolean) { appActivity += active }
        override fun startServer() {}
        override fun stopServer() {}
        override fun startClient() {}
        override fun stopClient() {}
        override fun getConnectedDeviceEntries(): List<Triple<String, Boolean, Int?>> = emptyList()
        override fun getLocalAdapterAddress(): String? = null
        override fun connectToAddress(address: String): Boolean = true
        override fun disconnectAddress(address: String) {}
        override fun getDebugInfo(): String = ""
    }

    private fun packet(marker: ULong): BitchatPacket = BitchatPacket(
        type = MessageType.MESSAGE.value,
        senderID = ByteArray(8),
        recipientID = null,
        timestamp = marker,
        payload = ByteArray(0),
        ttl = 1u,
    )

    @Test
    fun `overflow drops oldest frames, keeps newest, and counts every drop`() {
        val telemetry = CountingTelemetry()
        val transport = FakeBearerTransport()
        val bearer = BleBearer(
            myPeerID = "1111111111111111",
            debugSettingsManager = telemetry,
            connectionManagerFactory = { transport },
        )
        val delegate = requireNotNull(transport.delegate) { "bearer must install a transport delegate" }

        val overflow = 44
        val total = capacity + overflow
        // No consumer attached yet: the bounded channel fills to `capacity`, then DROP_OLDEST
        // sheds the stalest frame on each further send.
        repeat(total) { i -> delegate.onPacketReceived(packet(i.toULong()), "peerA", null) }

        assertEquals("every overflow must be counted in telemetry", overflow, telemetry.dropped.get())

        val received = mutableListOf<RoutedPacket>()
        runBlocking {
            withTimeoutOrNull(2_000) {
                bearer.incoming.take(capacity).collect { received.add(it) }
            }
        }

        assertEquals("buffer retains exactly `capacity` frames", capacity, received.size)
        assertEquals(
            "oldest surviving frame is marker $overflow (0..${overflow - 1} dropped)",
            overflow.toULong(),
            received.first().packet.timestamp,
        )
        assertEquals(
            "newest frame is always retained",
            (total - 1).toULong(),
            received.last().packet.timestamp,
        )
    }

    @Test
    fun `app lifecycle state is forwarded to the platform bearer`() {
        val transport = FakeBearerTransport()
        val bearer = BleBearer(
            myPeerID = "1111111111111111",
            debugSettingsManager = NoOpMeshTelemetry,
            connectionManagerFactory = { transport },
        )

        bearer.setAppIsActive(false)
        bearer.setAppIsActive(true)

        assertEquals(listOf(false, true), transport.appActivity)
    }
}
