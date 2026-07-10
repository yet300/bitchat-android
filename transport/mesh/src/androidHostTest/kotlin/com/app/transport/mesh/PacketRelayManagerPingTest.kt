package com.app.transport.mesh

import com.app.transport.MeshDebugToggles
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.SpecialRecipients
import com.app.transport.protocol.peerIdToRoutingBytes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how directed ping/pong traverse the relay policy.
 *
 * The reference (BLEReceivePipeline) folds directed ping/pong into `isDirectedEncrypted`: they get
 * the deterministic always-relay treatment of DMs rather than the degree-based broadcast TTL clamp,
 * so the hop count a probe reports reflects the path the packet really took. A dense-graph broadcast
 * at TTL 7 would come out at TTL 4; a directed probe must come out at TTL 6.
 */
class PacketRelayManagerPingTest {

    private companion object {
        const val ME = "1122334455667700"
        const val OTHER = "aabbccddeeff0011"
        const val SENDER = "cafecafecafecafe"
        const val DENSE_DEGREE = 8
    }

    private class Toggles(relayEnabled: Boolean = true) : MeshDebugToggles {
        private val on = MutableStateFlow(true)
        override val gattServerEnabled: StateFlow<Boolean> = on
        override val gattClientEnabled: StateFlow<Boolean> = on
        override val packetRelayEnabled: StateFlow<Boolean> = MutableStateFlow(relayEnabled)
        private val limit = MutableStateFlow(8)
        override val maxConnectionsOverall: StateFlow<Int> = limit
        override val maxServerConnections: StateFlow<Int> = limit
        override val maxClientConnections: StateFlow<Int> = limit
    }

    private val relayed = mutableListOf<BitchatPacket>()

    private fun manager(): PacketRelayManager = PacketRelayManager(ME, Toggles()).apply {
        delegate = object : PacketRelayManagerDelegate {
            override fun getNetworkSize(): Int = 40
            override fun getLocalDegree(): Int = DENSE_DEGREE
            override fun getBroadcastRecipient(): ByteArray = SpecialRecipients.BROADCAST
            override fun broadcastPacket(routed: RoutedPacket) { relayed.add(routed.packet) }
            override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean = false
        }
    }

    private fun packet(type: UByte, recipient: String?, ttl: UByte = 7u) = RoutedPacket(
        packet = BitchatPacket(
            version = 1u,
            type = type,
            senderID = peerIdToRoutingBytes(SENDER),
            recipientID = recipient?.let { peerIdToRoutingBytes(it) },
            timestamp = 1uL,
            payload = ByteArray(9),
            signature = null,
            ttl = ttl,
        ),
        peerID = SENDER,
    )

    @Test
    fun `directed ping for another peer relays deterministically without the dense TTL clamp`() {
        manager().handlePacketRelay(packet(MessageType.PING.value, recipient = OTHER))

        assertEquals(1, relayed.size)
        assertEquals("directed probe: ttl-1, not the dense clamp to 4", 6, relayed.single().ttl.toInt())
    }

    @Test
    fun `directed pong for another peer relays deterministically without the dense TTL clamp`() {
        manager().handlePacketRelay(packet(MessageType.PONG.value, recipient = OTHER))

        assertEquals(1, relayed.size)
        assertEquals(6, relayed.single().ttl.toInt())
    }

    @Test
    fun `ping addressed to us is never relayed`() {
        manager().handlePacketRelay(packet(MessageType.PING.value, recipient = ME))

        assertTrue(relayed.isEmpty())
    }

    @Test
    fun `pong addressed to us is never relayed`() {
        manager().handlePacketRelay(packet(MessageType.PONG.value, recipient = ME))

        assertTrue(relayed.isEmpty())
    }

    /**
     * Guard on the `isDirected` term: a ping crafted with the broadcast recipient is not directed,
     * so it must fall back to the degree-based broadcast clamp instead of the always-relay path.
     */
    @Test
    fun `ping with the broadcast recipient falls back to the broadcast clamp`() {
        val broadcastPing = RoutedPacket(
            packet = BitchatPacket(
                version = 1u,
                type = MessageType.PING.value,
                senderID = peerIdToRoutingBytes(SENDER),
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = 1uL,
                payload = ByteArray(9),
                signature = null,
                ttl = 7u,
            ),
            peerID = SENDER,
        )

        manager().handlePacketRelay(broadcastPing)

        assertEquals(1, relayed.size)
        assertEquals("dense clamp: min(7,5) - 1", 4, relayed.single().ttl.toInt())
    }
}
