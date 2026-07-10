package com.app.transport.mesh

import com.app.common.AppDispatchers
import com.app.transport.NoOpMeshTelemetry
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * S2 fix: packets from a single peer must be processed in strict arrival order. The old
 * `processorScope.launch { actor.send(routed) }` spawned one coroutine per packet on a
 * multi-thread dispatcher, so two sends for the same peer could reach the actor channel out of
 * order — fatal for Noise, where a reordered handshake message forces a re-handshake. The fix
 * enqueues with a direct `trySend` from the single incoming collector, preserving FIFO.
 */
class PacketProcessorFifoOrderTest {

    private val myPeerID = "1111111111111111"

    private class RecordingDelegate(val order: MutableList<Int>) : PacketProcessorDelegate {
        override fun validatePacketSecurity(packet: BitchatPacket, peerID: String) =
            PacketValidationResult.ACCEPT
        override fun handleDuplicateAnnounceLiveness(routed: RoutedPacket) {}
        override fun updatePeerLastSeen(peerID: String) {}
        override fun getPeerNickname(peerID: String): String? = null
        override fun getNetworkSize(): Int = 1
        override fun getLocalDegree(): Int = 1
        override fun getBroadcastRecipient(): ByteArray = ByteArray(0)
        override suspend fun handleNoiseHandshake(routed: RoutedPacket): Boolean = true
        override fun handleNoiseEncrypted(routed: RoutedPacket) {}
        override fun handleAnnounce(routed: RoutedPacket) {}
        override fun handleMessage(routed: RoutedPacket) {
            order.add(routed.packet.timestamp.toInt())
        }
        override fun handleLeave(routed: RoutedPacket) {}
        override fun handleFragment(packet: BitchatPacket): BitchatPacket? = null
        override fun handleRequestSync(routed: RoutedPacket) {}
        override fun handlePing(routed: RoutedPacket, linkKey: String) {}
        override fun handlePong(routed: RoutedPacket) {}
        override fun sendAnnouncementToPeer(peerID: String) {}
        override fun sendCachedMessages(peerID: String) {}
        override fun relayPacket(routed: RoutedPacket) {}
        override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean = true
    }

    private fun messageFrom(peerID: String, marker: Int): RoutedPacket {
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            senderID = ByteArray(8),
            // Directed (non-broadcast) so the P5 public-intake rate limiter does not
            // gate the burst — this test samples pure per-peer ordering, not policy.
            recipientID = ByteArray(8) { 0x42 },
            timestamp = marker.toULong(),
            payload = "m$marker".encodeToByteArray(),
            ttl = 1u,
        )
        return RoutedPacket(packet, peerID = peerID)
    }

    @Test
    fun `packets from one peer are processed in arrival order`() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val order = CopyOnWriteArrayList<Int>()
        val processor = PacketProcessor(
            myPeerID,
            NoOpMeshTelemetry,
            AppDispatchers(default = dispatcher, io = dispatcher),
        )
        processor.delegate = RecordingDelegate(order)

        // Stay under the per-peer channel capacity (128) so nothing is dropped and every packet
        // is a pure ordering sample.
        val count = 100
        try {
            repeat(count) { i -> processor.processPacket(messageFrom("peerA", i)) }

            runBlocking {
                withTimeoutOrNull(5_000) {
                    while (order.size < count) delay(5)
                }
            }

            assertEquals("all packets must be processed", count, order.size)
            assertEquals(
                "per-peer processing order must match arrival order",
                (0 until count).toList(),
                order.toList(),
            )
        } finally {
            processor.shutdown()
            dispatcher.close()
            executor.shutdownNow()
        }
    }
}
