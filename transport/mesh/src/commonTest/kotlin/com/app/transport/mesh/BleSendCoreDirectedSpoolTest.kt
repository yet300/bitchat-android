package com.app.transport.mesh

import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.SpecialRecipients
import com.app.transport.protocol.peerIdToRoutingBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Directed packets must not be dropped while the radio has zero writable neighbors;
 * they park in [BleDirectedRelaySpool] and leave after [BleSendCore.flushDirectedSpool].
 */
class BleSendCoreDirectedSpoolTest {

    private companion object {
        const val MY_PEER = "0a0b0c0d0e0f1011"
        const val PEER = "1111111111111111"
        const val ADDR = "link-1"
    }

    private class FakeRadio : BleRadioLink {
        val links = mutableListOf<BleNeighbor>()
        val emissions = mutableListOf<String>()

        override fun neighbors(): List<BleNeighbor> = links.toList()
        override fun peerForAddress(linkAddress: String): String? =
            links.firstOrNull { it.linkAddress == linkAddress }?.peerID

        override fun writeToNeighbor(neighbor: BleNeighbor, frame: ByteArray): Boolean {
            emissions.add(neighbor.linkAddress)
            return true
        }
    }

    private fun directedPacket() = BitchatPacket(
        version = 1u,
        type = MessageType.NOISE_ENCRYPTED.value,
        senderID = peerIdToRoutingBytes(MY_PEER),
        recipientID = peerIdToRoutingBytes(PEER),
        timestamp = 1_700_000_000_000uL,
        payload = byteArrayOf(0x01, 0x02),
        signature = null,
        ttl = 7u,
    )

    private fun CoroutineScope.core(radio: FakeRadio) = BleSendCore(
        scope = this,
        fragmentManager = FragmentManager(),
        transferProgressManager = TransferProgressManager(),
        myPeerID = MY_PEER,
        radio = radio,
        trafficLog = null,
        sourceRoutingEnabled = false,
        logTag = "spool-test",
    )

    @Test
    fun broadcastWhileNoLinks_spoolsThenFlushesOnLinkUp() = runTest {
        val radio = FakeRadio()
        val core = core(radio)

        core.broadcastPacket(RoutedPacket(directedPacket(), directedPeerID = PEER))
        advanceUntilIdle()
        assertTrue(radio.emissions.isEmpty(), "no neighbors ⇒ nothing on the air yet")

        radio.links += BleNeighbor(ADDR, isClient = true, peerID = PEER)
        core.flushDirectedSpool()
        advanceUntilIdle()

        assertEquals(listOf(ADDR), radio.emissions)
        core.shutdown()
    }

    @Test
    fun broadcastWhileLinked_doesNotSpool() = runTest {
        val radio = FakeRadio()
        radio.links += BleNeighbor(ADDR, isClient = true, peerID = PEER)
        val core = core(radio)

        core.broadcastPacket(RoutedPacket(directedPacket(), directedPeerID = PEER))
        advanceUntilIdle()
        assertEquals(listOf(ADDR), radio.emissions)

        core.flushDirectedSpool()
        advanceUntilIdle()
        assertEquals(1, radio.emissions.size)
        core.shutdown()
    }

    @Test
    fun publicBroadcastWithNoLinks_isNotSpoolFlushed() = runTest {
        val radio = FakeRadio()
        val core = core(radio)
        val public = BitchatPacket(
            type = MessageType.MESSAGE.value,
            senderID = peerIdToRoutingBytes(MY_PEER),
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = 1u,
            payload = "hi".encodeToByteArray(),
            ttl = 7u,
        )
        core.broadcastPacket(RoutedPacket(public))
        advanceUntilIdle()

        radio.links += BleNeighbor(ADDR, isClient = true, peerID = PEER)
        core.flushDirectedSpool()
        advanceUntilIdle()
        assertTrue(radio.emissions.isEmpty(), "public traffic must not ride the directed spool")
        core.shutdown()
    }
}
