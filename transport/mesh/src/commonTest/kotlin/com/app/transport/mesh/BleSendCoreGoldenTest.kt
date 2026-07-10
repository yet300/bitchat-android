package com.app.transport.mesh

import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.SpecialRecipients
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GOLDEN target-set/byte characterization of the shared [BleSendCore] — the single send tree both
 * BLE bearers route through after C1. The `sourceRoutingEnabled = false` cases pin the Apple
 * (CoreBluetooth) target set, which cannot be exercised on the iOS simulator (no BLE radio); the
 * `= true` cases pin the Android set. Must match the platform behavior frozen in
 * BleSendPathGoldenTest — do not update expectations, fix the code.
 *
 * SYNC_SCALE P4 (owner-approved): frame BYTES stay frozen, but non-exempt broadcasts now
 * target the deterministic BLEFanoutSelector subset instead of all links. Target-set
 * expectations were updated deliberately for that policy; byte vectors were not.
 */
class BleSendCoreGoldenTest {

    private companion object {
        const val MY_PEER = "0a0b0c0d0e0f1011"
        const val PEER_SRV_1 = "1111111111111111"
        const val PEER_SRV_2 = "2222222222222222"
        const val PEER_CLI_1 = "3333333333333333"
        const val ADDR_SRV_1 = "srv-1"
        const val ADDR_SRV_2 = "srv-2"
        const val ADDR_CLI_1 = "cli-1"
        const val ADDR_CLI_2 = "cli-2"
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
        links += BleNeighbor(ADDR_CLI_2, isClient = true, peerID = null)
    }

    private fun CoroutineScope.core(sourceRouting: Boolean) = BleSendCore(
        scope = this,
        fragmentManager = FragmentManager(),
        transferProgressManager = TransferProgressManager(),
        myPeerID = MY_PEER,
        radio = radio,
        trafficLog = null,
        sourceRoutingEnabled = sourceRouting,
        logTag = "test",
    )

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte() }

    private fun packet(
        type: UByte = MessageType.MESSAGE.value,
        sender: String = "4444444444444444",
        recipient: ByteArray? = SpecialRecipients.BROADCAST,
        route: List<ByteArray>? = null,
    ) = BitchatPacket(
        version = if (route != null) 2u else 1u,
        type = type,
        senderID = hexToBytes(sender),
        recipientID = recipient,
        timestamp = 1_700_000_000_000uL,
        payload = "golden payload".encodeToByteArray(),
        ttl = 5u,
        route = route,
    )

    private fun expectedFrame(p: BitchatPacket): ByteArray =
        p.toBinaryData(padding = BLEPacketPaddingPolicy.shouldPadForBLE(p.type))!!

    /**
     * SYNC_SCALE P4: non-exempt broadcasts go to a deterministic per-message fanout
     * SUBSET of the eligible links (iOS BLEFanoutSelector), not all of them. Frame
     * BYTES are unchanged — only the target-link selection is a local relay policy.
     * Expected targets are computed with the same deterministic selector.
     */
    private fun expectedBroadcastTargets(
        p: BitchatPacket,
        eligible: List<String>,
    ): List<String> {
        if (!BleFanoutSelector.shouldSubset(p.type)) return eligible
        val chosen = BleFanoutSelector.selectSubset(
            linkIds = eligible,
            k = BleFanoutSelector.subsetSize(eligible.size),
            seed = com.app.transport.sync.PacketIdUtil.computeIdHex(p),
        )
        return eligible.filter { it in chosen }
    }

    @Test
    fun `broadcast MESSAGE hits the deterministic fanout subset - servers-first order - exact bytes`() = runTest {
        val core = core(sourceRouting = false)
        val p = packet()
        core.broadcastPacket(RoutedPacket(p))
        advanceUntilIdle()

        val allLinks = listOf(ADDR_SRV_1, ADDR_SRV_2, ADDR_CLI_1, ADDR_CLI_2)
        val expected = expectedBroadcastTargets(p, allLinks)
        assertEquals(3, expected.size) // subsetSize(4) = 3
        assertEquals(expected, radio.emissions.map { it.first })
        radio.emissions.forEach { assertContentEquals(expectedFrame(p), it.second) }
        core.shutdown()
    }

    @Test
    fun `broadcast ANNOUNCE bypasses the subset and hits ALL links in order`() = runTest {
        val core = core(sourceRouting = false)
        val p = packet(type = MessageType.ANNOUNCE.value)
        core.broadcastPacket(RoutedPacket(p))
        advanceUntilIdle()

        assertEquals(listOf(ADDR_SRV_1, ADDR_SRV_2, ADDR_CLI_1, ADDR_CLI_2), radio.emissions.map { it.first })
        radio.emissions.forEach { assertContentEquals(expectedFrame(p), it.second) }
        core.shutdown()
    }

    @Test
    fun `broadcast REQUEST_SYNC bypasses the subset and hits ALL links`() = runTest {
        val core = core(sourceRouting = false)
        val p = packet(type = MessageType.REQUEST_SYNC.value)
        core.broadcastPacket(RoutedPacket(p))
        advanceUntilIdle()

        assertEquals(listOf(ADDR_SRV_1, ADDR_SRV_2, ADDR_CLI_1, ADDR_CLI_2), radio.emissions.map { it.first })
        core.shutdown()
    }

    @Test
    fun `anti-loop skips relayer link and sender link`() = runTest {
        val core = core(sourceRouting = false)
        val p = packet(sender = PEER_CLI_1)
        core.broadcastPacket(RoutedPacket(p, peerID = PEER_CLI_1, relayAddress = ADDR_SRV_1))
        advanceUntilIdle()

        assertEquals(listOf(ADDR_SRV_2, ADDR_CLI_2), radio.emissions.map { it.first })
        core.shutdown()
    }

    @Test
    fun `directed send prefers server link and stops after success`() = runTest {
        val core = core(sourceRouting = false)
        val p = packet(recipient = hexToBytes(PEER_SRV_2))
        core.broadcastPacket(RoutedPacket(p))
        advanceUntilIdle()

        assertEquals(listOf(ADDR_SRV_2), radio.emissions.map { it.first })
        core.shutdown()
    }

    @Test
    fun `directed send falls back to broadcast when write fails everywhere`() = runTest {
        radio.failAddresses = setOf(ADDR_SRV_2)
        val core = core(sourceRouting = false)
        val p = packet(recipient = hexToBytes(PEER_SRV_2))
        core.broadcastPacket(RoutedPacket(p))
        advanceUntilIdle()

        // Recipient link write failed -> historical fallback: broadcast, now through the
        // P4 fanout subset; a chosen link whose write fails emits nothing.
        val allLinks = listOf(ADDR_SRV_1, ADDR_SRV_2, ADDR_CLI_1, ADDR_CLI_2)
        val expected = expectedBroadcastTargets(p, allLinks).filter { it != ADDR_SRV_2 }
        assertEquals(expected, radio.emissions.map { it.first })
        core.shutdown()
    }

    @Test
    fun `apple parity - route on own packet is IGNORED when source routing is off`() = runTest {
        val core = core(sourceRouting = false)
        val p = packet(
            sender = MY_PEER,
            recipient = hexToBytes("9999999999999999"),
            route = listOf(hexToBytes(PEER_CLI_1)),
        )
        core.broadcastPacket(RoutedPacket(p))
        advanceUntilIdle()

        // Pre-C1 CoreBluetooth behavior: no source routing -> unknown recipient -> broadcast
        // (P4: through the deterministic fanout subset for MESSAGE).
        val expected = expectedBroadcastTargets(p, listOf(ADDR_SRV_1, ADDR_SRV_2, ADDR_CLI_1, ADDR_CLI_2))
        assertEquals(expected, radio.emissions.map { it.first })
        core.shutdown()
    }

    @Test
    fun `android parity - own routed packet goes only to first hop when source routing is on`() = runTest {
        val core = core(sourceRouting = true)
        val p = packet(
            sender = MY_PEER,
            recipient = hexToBytes("9999999999999999"),
            route = listOf(hexToBytes(PEER_CLI_1)),
        )
        core.broadcastPacket(RoutedPacket(p))
        advanceUntilIdle()

        assertEquals(listOf(ADDR_CLI_1), radio.emissions.map { it.first })
        assertContentEquals(expectedFrame(p), radio.emissions[0].second)
        core.shutdown()
    }

    @Test
    fun `sendToPeer is synchronous - server-first - false for unknown peer`() = runTest {
        val core = core(sourceRouting = false)
        val p = packet(recipient = hexToBytes(PEER_SRV_1))

        assertTrue(core.sendToPeer(PEER_SRV_1, RoutedPacket(p)))
        assertEquals(listOf(ADDR_SRV_1), radio.emissions.map { it.first }) // no advanceUntilIdle needed
        assertContentEquals(expectedFrame(p), radio.emissions[0].second)

        assertFalse(core.sendToPeer("9999999999999999", RoutedPacket(p)))
        assertEquals(1, radio.emissions.size)
        core.shutdown()
        advanceUntilIdle()
    }

    @Test
    fun `noise frames leave the core padded`() = runTest {
        val core = core(sourceRouting = false)
        val p = packet(type = MessageType.NOISE_ENCRYPTED.value, recipient = hexToBytes(PEER_SRV_1))
        core.broadcastPacket(RoutedPacket(p))
        advanceUntilIdle()

        assertContentEquals(expectedFrame(p), radio.emissions.single().second)
        assertTrue(radio.emissions.single().second.size != p.toBinaryData(padding = false)!!.size)
        core.shutdown()
    }
}
