package com.app.transport.sync

import com.app.common.AppDispatchers
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the per-peer REQUEST_SYNC rate limiting: every direct announce schedules a
 * per-peer sync, and peers answer sync requests by resending cached announces —
 * without a floor on the SENT interval the two amplify into a request storm
 * (observed on-device as one sync request per second per peer).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GossipSyncManagerRateLimitTest {

    private class RecordingDelegate : GossipSyncManager.Delegate {
        val broadcasts = mutableListOf<BitchatPacket>()
        val unicasts = mutableListOf<Pair<String, BitchatPacket>>()
        override fun sendPacket(packet: BitchatPacket) { broadcasts.add(packet) }
        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
            unicasts.add(peerID to packet)
        }
        override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket = packet
    }

    private val config = object : GossipSyncManager.ConfigProvider {
        override fun seenCapacity(): Int = 100
        override fun gcsMaxBytes(): Int = 400
        override fun gcsTargetFpr(): Double = 0.01
    }

    private fun TestScope.manager(delegate: RecordingDelegate): GossipSyncManager {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return GossipSyncManager(
            myPeerID = "1111222233334444",
            scope = this,
            configProvider = config,
            dispatchers = AppDispatchers(
                default = dispatcher,
                io = dispatcher,
                unconfined = dispatcher,
            ),
            nowMillis = { testScheduler.currentTime },
        ).also { it.delegate = delegate }
    }

    @Test
    fun burstOfScheduleCallsProducesExactlyOneSend() = kotlinx.coroutines.test.runTest {
        val delegate = RecordingDelegate()
        val manager = manager(delegate)

        repeat(50) { manager.scheduleInitialSyncToPeer("aaaabbbbccccdddd", 1_000) }
        advanceTimeBy(10_000); runCurrent()

        assertEquals(1, delegate.unicasts.size)
        assertEquals(MessageType.REQUEST_SYNC.value, delegate.unicasts[0].second.type)

        // Still within the interval: further schedule calls stay no-ops.
        repeat(10) { manager.scheduleInitialSyncToPeer("aaaabbbbccccdddd", 1_000) }
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, delegate.unicasts.size)
    }

    @Test
    fun secondSendAllowedAfterInterval() = kotlinx.coroutines.test.runTest {
        val delegate = RecordingDelegate()
        val manager = manager(delegate)
        val peer = "aaaabbbbccccdddd"

        manager.scheduleInitialSyncToPeer(peer, 1_000)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, delegate.unicasts.size)

        // Just under the interval since the SEND: still blocked.
        advanceTimeBy(GossipSyncManager.MIN_PEER_SYNC_INTERVAL_MS - 2_000); runCurrent()
        manager.scheduleInitialSyncToPeer(peer, 1_000)
        runCurrent()
        assertEquals(1, delegate.unicasts.size)

        // Past the interval: a new request goes out.
        advanceTimeBy(GossipSyncManager.MIN_PEER_SYNC_INTERVAL_MS); runCurrent()
        manager.scheduleInitialSyncToPeer(peer, 1_000)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(2, delegate.unicasts.size)
    }

    @Test
    fun rateLimitIsPerPeer() = kotlinx.coroutines.test.runTest {
        val delegate = RecordingDelegate()
        val manager = manager(delegate)

        manager.scheduleInitialSyncToPeer("aaaabbbbccccdddd", 1_000)
        manager.scheduleInitialSyncToPeer("9999888877776666", 1_000)
        advanceTimeBy(2_000); runCurrent()

        assertEquals(2, delegate.unicasts.size)
        assertEquals(setOf("aaaabbbbccccdddd", "9999888877776666"), delegate.unicasts.map { it.first }.toSet())
    }

    @Test
    fun peerRemovalResetsRateLimit() = kotlinx.coroutines.test.runTest {
        val delegate = RecordingDelegate()
        val manager = manager(delegate)
        val peer = "aaaabbbbccccdddd"

        manager.scheduleInitialSyncToPeer(peer, 1_000)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, delegate.unicasts.size)

        // Peer leaves and reconnects: the initial sync must not wait out the interval.
        manager.removeAnnouncementForPeer(peer)
        manager.scheduleInitialSyncToPeer(peer, 1_000)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(2, delegate.unicasts.size)
    }
}
