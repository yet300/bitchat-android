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
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class PacketProcessorVoiceFrameTest {
    private class RecordingDelegate(private val acceptVoiceFrame: Boolean) : PacketProcessorDelegate {
        val received = CopyOnWriteArrayList<ULong>()
        val relayed = CopyOnWriteArrayList<ULong>()

        override fun validatePacketSecurity(packet: BitchatPacket, peerID: String) = PacketValidationResult.ACCEPT
        override fun handleDuplicateAnnounceLiveness(routed: RoutedPacket) = Unit
        override fun updatePeerLastSeen(peerID: String) = Unit
        override fun getPeerNickname(peerID: String): String? = null
        override fun getNetworkSize(): Int = 1
        override fun getLocalDegree(): Int = 1
        override fun getBroadcastRecipient(): ByteArray = SpecialRecipients.BROADCAST
        override suspend fun handleNoiseHandshake(routed: RoutedPacket): Boolean = true
        override fun handleNoiseEncrypted(routed: RoutedPacket) = Unit
        override fun handleAnnounce(routed: RoutedPacket) = Unit
        override fun handleMessage(routed: RoutedPacket) = Unit
        override fun handleLeave(routed: RoutedPacket) = Unit
        override fun handleFragment(packet: BitchatPacket): BitchatPacket? = null
        override fun handleRequestSync(routed: RoutedPacket) = Unit
        override fun handlePing(routed: RoutedPacket, linkKey: String) = Unit
        override fun handlePong(routed: RoutedPacket) = Unit
        override fun handleCourierEnvelope(routed: RoutedPacket) = Unit
        override fun handleGroupMessage(routed: RoutedPacket) = Unit
        override fun handlePrekeyBundle(routed: RoutedPacket) = Unit
        override fun handleBoardPost(routed: RoutedPacket): Boolean = true
        override fun handleVoiceFrame(routed: RoutedPacket): Boolean {
            received += routed.packet.timestamp
            return acceptVoiceFrame
        }
        override fun sendAnnouncementToPeer(peerID: String) = Unit
        override fun sendCachedMessages(peerID: String) = Unit
        override fun relayPacket(routed: RoutedPacket) { relayed += routed.packet.timestamp }
        override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean = true
    }

    @Test
    fun `rejected voice frame is neither emitted nor relayed`() = assertVoiceFrameRelay(false, 0)

    @Test
    fun `accepted voice frame is emitted then relayed`() = assertVoiceFrameRelay(true, 1)

    private fun assertVoiceFrameRelay(accepted: Boolean, expectedRelays: Int) {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val delegate = RecordingDelegate(accepted)
        val processor = PacketProcessor(
            myPeerID = "1111111111111111",
            debugSettingsManager = NoOpMeshTelemetry,
            dispatchers = AppDispatchers(default = dispatcher, io = dispatcher),
        ).also { it.delegate = delegate }
        try {
            processor.processPacket(
                RoutedPacket(
                    BitchatPacket(
                        type = MessageType.VOICE_FRAME.value,
                        senderID = ByteArray(8) { 0x44 },
                        recipientID = SpecialRecipients.BROADCAST,
                        timestamp = 42u,
                        payload = byteArrayOf(1),
                        ttl = 5u,
                    ),
                    peerID = "2222222222222222",
                ),
            )
            runBlocking {
                withTimeout(5_000) { while (delegate.received.size != 1) delay(5) }
                if (accepted) withTimeout(5_000) { while (delegate.relayed.size != expectedRelays) delay(5) }
                else delay(100)
            }
            assertEquals(listOf(42uL), delegate.received.toList())
            assertEquals(expectedRelays, delegate.relayed.size)
        } finally {
            processor.shutdown()
            dispatcher.close()
            executor.shutdownNow()
        }
    }
}
