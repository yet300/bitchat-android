@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.app.transport.sync

import com.app.common.AppDispatchers
import com.app.transport.model.RequestSyncPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Clock

/**
 * Pins the REQUEST_SYNC response throttle inside GossipSyncManager (SYNC_SCALE P2):
 * at most 8 diff passes per requester per 30 s, gated BEFORE any store scan; the
 * window is sliding and reopens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GossipSyncManagerResponseThrottleTest {

    private class RecordingDelegate : GossipSyncManager.Delegate {
        val unicasts = mutableListOf<Pair<String, BitchatPacket>>()
        override fun sendPacket(packet: BitchatPacket) {}
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

    /** One stored announce -> each allowed diff pass emits exactly one unicast. */
    private fun GossipSyncManager.seedOneAnnounce() {
        onPublicPacketSeen(
            BitchatPacket(
                type = MessageType.ANNOUNCE.value,
                senderID = ByteArray(8) { 0x0B },
                timestamp = Clock.System.now().toEpochMilliseconds().toULong(),
                payload = byteArrayOf(1, 2, 3),
                ttl = 7u,
            )
        )
    }

    private val emptyFilter = RequestSyncPacket(p = 7, m = 1, data = ByteArray(0))

    @Test
    fun ninthRequestWithinWindowGetsNoResponse() = runTest {
        val delegate = RecordingDelegate()
        val manager = manager(delegate).apply { seedOneAnnounce() }

        repeat(9) { manager.handleRequestSync("aaaabbbbccccdddd", emptyFilter) }
        assertEquals("only 8 of 9 requests inside the window are served", 8, delegate.unicasts.size)
    }

    @Test
    fun windowReopensAfterThirtySeconds() = runTest {
        val delegate = RecordingDelegate()
        val manager = manager(delegate).apply { seedOneAnnounce() }

        repeat(9) { manager.handleRequestSync("aaaabbbbccccdddd", emptyFilter) }
        assertEquals(8, delegate.unicasts.size)

        // Advance virtual time past the sliding window: budget replenishes.
        advanceTimeBy(SyncResponseRateLimiter.WINDOW_MILLIS + 1)
        manager.handleRequestSync("aaaabbbbccccdddd", emptyFilter)
        assertEquals(9, delegate.unicasts.size)
    }

    @Test
    fun budgetIsPerRequester() = runTest {
        val delegate = RecordingDelegate()
        val manager = manager(delegate).apply { seedOneAnnounce() }

        repeat(9) { manager.handleRequestSync("aaaabbbbccccdddd", emptyFilter) }
        repeat(2) { manager.handleRequestSync("9999888877776666", emptyFilter) }
        assertEquals(10, delegate.unicasts.size)
        assertEquals(2, delegate.unicasts.count { it.first == "9999888877776666" })
    }
}
