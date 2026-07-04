package com.app.transport.mesh

import com.app.transport.NoOpMeshTelemetry
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Hardening regression for the per-peer actor bound (robustness review H1): peerID is derived from
 * the attacker-controlled packet.senderID and the actor is created before validation, so the actor
 * map must not grow without bound when a peer spoofs many distinct sender IDs.
 */
class PacketProcessorActorBoundTest {

    private val myPeerID = "1111111111111111"

    private fun packetFrom(peerHex: String): RoutedPacket {
        // The actor map is keyed on RoutedPacket.peerID; the 8-byte senderID content is irrelevant
        // to this test (the null-delegate path drops the packet after the actor is created).
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            senderID = ByteArray(8),
            recipientID = null,
            timestamp = 1L.toULong(),
            payload = "x".toByteArray(),
            ttl = 3u,
        )
        return RoutedPacket(packet, peerID = peerHex)
    }

    @Test
    fun `actor map is bounded under distinct sender ids`() {
        val processor = PacketProcessor(myPeerID, NoOpMeshTelemetry)
        try {
            // Flood far past the cap with distinct spoofed sender IDs.
            repeat(1_000) { i ->
                processor.processPacket(packetFrom(i.toString(16).padStart(16, '0')))
            }

            val activeActors = Regex("Active Peer Actors: (\\d+)")
                .find(processor.getDebugInfo())
                ?.groupValues?.get(1)?.toInt() ?: -1

            assertTrue(
                activeActors in 1..256,
                "Per-peer actor map must stay bounded (<=256); was $activeActors",
            )
        } finally {
            processor.shutdown()
        }
    }
}
