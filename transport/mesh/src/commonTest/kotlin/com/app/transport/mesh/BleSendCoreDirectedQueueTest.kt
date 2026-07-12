package com.app.transport.mesh

import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.SpecialRecipients
import com.app.transport.model.FragmentPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the queued directed-send path (SYNC_SCALE P2): a RoutedPacket carrying
 * [RoutedPacket.directedPeerID] rides the bounded priority send queue and is
 * delivered ONLY to the target peer's link (broadcast fallback when the link is
 * gone), at relay/bulk priority. Frame bytes are identical to the direct path.
 */
class BleSendCoreDirectedQueueTest {

    private companion object {
        const val MY_PEER = "0a0b0c0d0e0f1011"
        const val PEER_SRV_1 = "1111111111111111"
        const val PEER_SRV_2 = "2222222222222222"
        const val PEER_CLI_1 = "3333333333333333"
        const val ADDR_SRV_1 = "srv-1"
        const val ADDR_SRV_2 = "srv-2"
        const val ADDR_CLI_1 = "cli-1"
    }

    private class FakeRadio : BleRadioLink {
        val links = mutableListOf<BleNeighbor>()
        val emissions = mutableListOf<Pair<String, ByteArray>>()
        var failAddresses = setOf<String>()

        override fun neighbors(): List<BleNeighbor> = links.toList()
        override fun peerForAddress(linkAddress: String): String? =
            links.firstOrNull { it.linkAddress == linkAddress }?.peerID

        override fun writeToNeighbor(neighbor: BleNeighbor, frame: ByteArray): Boolean {
            if (neighbor.linkAddress in failAddresses) return false
            emissions.add(neighbor.linkAddress to frame.copyOf())
            return true
        }
    }

    private val radio = FakeRadio().apply {
        links += BleNeighbor(ADDR_SRV_1, isClient = false, peerID = PEER_SRV_1)
        links += BleNeighbor(ADDR_SRV_2, isClient = false, peerID = PEER_SRV_2)
        links += BleNeighbor(ADDR_CLI_1, isClient = true, peerID = PEER_CLI_1)
    }

    private fun CoroutineScope.core() = BleSendCore(
        scope = this,
        fragmentManager = FragmentManager(),
        transferProgressManager = TransferProgressManager(),
        myPeerID = MY_PEER,
        radio = radio,
        trafficLog = null,
        sourceRoutingEnabled = false,
        logTag = "test",
    )

    /** A stored broadcast packet as replayed by a gossip-sync response. */
    private fun storedBroadcast() = BitchatPacket(
        type = MessageType.MESSAGE.value,
        senderID = ByteArray(8) { 0x44 },
        recipientID = SpecialRecipients.BROADCAST,
        timestamp = 1_700_000_000_000uL,
        payload = "sync payload".encodeToByteArray(),
        ttl = 0u,
    )

    @Test
    fun directedQueuedSendHitsOnlyTargetLink() = runTest {
        val core = core()
        val p = storedBroadcast()
        core.broadcastPacket(RoutedPacket(p, directedPeerID = PEER_SRV_2))
        advanceUntilIdle()

        assertEquals(listOf(ADDR_SRV_2), radio.emissions.map { it.first })
        // Byte-for-byte identical to the direct-write path encoding.
        assertContentEquals(
            p.toBinaryData(padding = BLEPacketPaddingPolicy.shouldPadForBLE(p.type))!!,
            radio.emissions.single().second,
        )
        core.shutdown()
    }

    @Test
    fun directedQueuedSendFallsBackToBroadcastWhenLinkGone() = runTest {
        val core = core()
        val p = storedBroadcast()
        core.broadcastPacket(RoutedPacket(p, directedPeerID = "9999999999999999"))
        advanceUntilIdle()

        // Target unknown on this radio: historical fallback floods remaining links.
        assertEquals(listOf(ADDR_SRV_1, ADDR_SRV_2, ADDR_CLI_1), radio.emissions.map { it.first })
        core.shutdown()
    }

    @Test
    fun directedFramesRideAtRelayBulkPriority() {
        val routed = RoutedPacket(storedBroadcast(), directedPeerID = PEER_SRV_1)
        assertEquals(BleOutboundPriority.RELAY_BULK, BleOutboundPriority.of(routed))
        // Without the directed marker the same frame would count as our own traffic.
        assertEquals(
            BleOutboundPriority.OWN_HIGH,
            BleOutboundPriority.of(RoutedPacket(storedBroadcast())),
        )
    }

    @Test
    fun `large directed nostr carrier is fragmented and every fragment stays on target link`() = runTest {
        val core = core()
        var prng = 0x13579BDF
        val carrier = BitchatPacket(
            type = MessageType.NOSTR_CARRIER.value,
            senderID = ByteArray(8) { 0x44 },
            recipientID = PEER_SRV_2.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            timestamp = 1_700_000_000_000uL,
            // Incompressible deterministic payload: a real signed Nostr JSON may not shrink
            // enough for the transport compressor to cross the BLE fragmentation threshold.
            payload = ByteArray(1_200) {
                prng = prng * 1_103_515_245 + 12_345
                (prng ushr 16).toByte()
            },
            ttl = 7u,
        )

        core.broadcastPacket(RoutedPacket(carrier, directedPeerID = PEER_SRV_2))
        advanceUntilIdle()

        assertTrue(radio.emissions.size > 1)
        assertEquals(setOf(ADDR_SRV_2), radio.emissions.map { it.first }.toSet())
        radio.emissions.forEach { (_, frame) ->
            val fragment = BitchatPacket.fromBinaryData(frame)!!
            assertEquals(MessageType.FRAGMENT.value, fragment.type)
            assertEquals(MessageType.NOSTR_CARRIER.value, FragmentPayload.decode(fragment.payload)!!.originalType)
        }
        core.shutdown()
    }
}
