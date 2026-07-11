package com.app.transport.mesh

import com.app.common.AppDispatchers
import com.app.transport.NoOpMeshTelemetry
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.SpecialRecipients
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * SYNC_SCALE P5 integration pin: the public broadcast MESSAGE intake in PacketProcessor
 * is gated by MessageRateLimiter (sender bucket capacity 5) BEFORE handleMessage and
 * before the relay step. Directed messages are not gated.
 */
class PacketProcessorMessageRateLimitTest {

    private val myPeerID = "1111111111111111"

    private class RecordingDelegate : PacketProcessorDelegate {
        val handled = CopyOnWriteArrayList<Int>()
        val relayed = CopyOnWriteArrayList<Int>()
        override fun validatePacketSecurity(packet: BitchatPacket, peerID: String) =
            PacketValidationResult.ACCEPT
        override fun handleDuplicateAnnounceLiveness(routed: RoutedPacket) {}
        override fun updatePeerLastSeen(peerID: String) {}
        override fun getPeerNickname(peerID: String): String? = null
        override fun getNetworkSize(): Int = 1
        override fun getLocalDegree(): Int = 1
        override fun getBroadcastRecipient(): ByteArray = SpecialRecipients.BROADCAST
        override suspend fun handleNoiseHandshake(routed: RoutedPacket): Boolean = true
        override fun handleNoiseEncrypted(routed: RoutedPacket) {}
        override fun handleAnnounce(routed: RoutedPacket) {}
        override fun handleMessage(routed: RoutedPacket) {
            handled.add(routed.packet.timestamp.toInt())
        }
        override fun handleLeave(routed: RoutedPacket) {}
        override fun handleFragment(packet: BitchatPacket): BitchatPacket? = null
        override fun handleRequestSync(routed: RoutedPacket) {}
        override fun handlePing(routed: RoutedPacket, linkKey: String) {}
        override fun handlePong(routed: RoutedPacket) {}
        override fun handleCourierEnvelope(routed: RoutedPacket) {}
        override fun handleGroupMessage(routed: RoutedPacket) {}
        override fun handleBoardPost(routed: RoutedPacket): Boolean = true
        override fun sendAnnouncementToPeer(peerID: String) {}
        override fun sendCachedMessages(peerID: String) {}
        override fun relayPacket(routed: RoutedPacket) {
            relayed.add(routed.packet.timestamp.toInt())
        }
        override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean = true
    }

    private fun broadcastMessage(marker: Int): RoutedPacket = RoutedPacket(
        BitchatPacket(
            type = MessageType.MESSAGE.value,
            senderID = ByteArray(8) { 0x0E },
            recipientID = null, // broadcast
            timestamp = marker.toULong(),
            payload = "burst-$marker".encodeToByteArray(),
            ttl = 5u,
        ),
        peerID = "eeeeffff00001111",
    )

    @Test
    fun burstBeyondSenderBucketIsDroppedBeforeStoreAndRelay() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val delegate = RecordingDelegate()
        var now = 1_000_000L
        val processor = PacketProcessor(
            myPeerID,
            NoOpMeshTelemetry,
            AppDispatchers(default = dispatcher, io = dispatcher),
            publicMessageRateLimiter = MessageRateLimiter(nowMillis = { now }),
        )
        processor.delegate = delegate

        try {
            // 8 distinct-content broadcasts from one sender in one instant:
            // sender bucket capacity 5 -> exactly 5 pass, 3 dropped pre-relay.
            repeat(8) { i -> processor.processPacket(broadcastMessage(i)) }
            runBlocking {
                withTimeoutOrNull(5_000) { while (delegate.handled.size < 5) delay(5) }
                delay(100) // settle: catch over-accepts
            }
            assertEquals(listOf(0, 1, 2, 3, 4), delegate.handled.toList())
            assertEquals("dropped messages must not be relayed", listOf(0, 1, 2, 3, 4), delegate.relayed.toList())

            // Refill 1.0/s: one more slot after a second.
            now += 1_000
            processor.processPacket(broadcastMessage(100))
            processor.processPacket(broadcastMessage(101))
            runBlocking {
                withTimeoutOrNull(5_000) { while (delegate.handled.size < 6) delay(5) }
                delay(100)
            }
            assertEquals(listOf(0, 1, 2, 3, 4, 100), delegate.handled.toList())
        } finally {
            processor.shutdown()
            dispatcher.close()
            executor.shutdownNow()
        }
    }
}
