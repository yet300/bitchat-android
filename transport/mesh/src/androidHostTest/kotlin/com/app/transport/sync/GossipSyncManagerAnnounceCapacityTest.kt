@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.app.transport.sync

import com.app.common.AppDispatchers
import com.app.transport.model.RequestSyncPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Clock

/**
 * Convergence pin for the 1000-peer announce store (SYNC_SCALE P1).
 *
 * The announce store used to share seenCapacity() (500) with messages and the GCS
 * filter cap: at N > 500 peers the requester evicted live announces from its own
 * store, could not represent them in its filter, and neighbors re-sent them every
 * round — re-insert, re-evict, thrash; gossip never reached steady state. Announces
 * are now bounded by the 180s liveness prune plus an independent LRU safety ceiling
 * (announceCapacity, default 2000) that only guards against peer-ID spoofing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GossipSyncManagerAnnounceCapacityTest {

    private class RecordingDelegate : GossipSyncManager.Delegate {
        val broadcasts = mutableListOf<BitchatPacket>()
        val unicasts = mutableListOf<Pair<String, BitchatPacket>>()
        override fun sendPacket(packet: BitchatPacket) { broadcasts.add(packet) }
        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
            unicasts.add(peerID to packet)
        }
        override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket = packet
    }

    /** seenCapacity deliberately BELOW the peer count to prove the stores are decoupled. */
    private fun config(announceCap: Int = SyncDefaults.DEFAULT_ANNOUNCE_CAPACITY) =
        object : GossipSyncManager.ConfigProvider {
            override fun seenCapacity(): Int = 500
            override fun gcsMaxBytes(): Int = 400
            override fun gcsTargetFpr(): Double = 0.01
            override fun announceCapacity(): Int = announceCap
        }

    private fun TestScope.manager(
        delegate: RecordingDelegate,
        configProvider: GossipSyncManager.ConfigProvider,
    ): GossipSyncManager {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return GossipSyncManager(
            myPeerID = "1111222233334444",
            scope = this,
            configProvider = configProvider,
            dispatchers = AppDispatchers(
                default = dispatcher,
                io = dispatcher,
                unconfined = dispatcher,
            ),
            nowMillis = { testScheduler.currentTime },
        ).also { it.delegate = delegate }
    }

    private fun announceFrom(index: Int): BitchatPacket {
        val sender = ByteArray(8)
        sender[0] = 0x0A
        sender[4] = ((index ushr 24) and 0xFF).toByte()
        sender[5] = ((index ushr 16) and 0xFF).toByte()
        sender[6] = ((index ushr 8) and 0xFF).toByte()
        sender[7] = (index and 0xFF).toByte()
        // Freshness in onPublicPacketSeen is checked against wall-clock time, so the
        // packet timestamp must be real "now" (not virtual test time).
        return BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            senderID = sender,
            timestamp = Clock.System.now().toEpochMilliseconds().toULong(),
            payload = byteArrayOf(index.toByte(), (index ushr 8).toByte()),
            ttl = 7u,
        )
    }

    @Test
    fun thousandLiveAnnouncesAreRetainedAndAllOffered() = runTest {
        val delegate = RecordingDelegate()
        val manager = manager(delegate, config())

        repeat(1000) { manager.onPublicPacketSeen(announceFrom(it)) }
        assertEquals("no live announce may be evicted", 1000, manager.storedAnnouncementCount())

        // Sync round: an empty requester filter (m=1, no data) misses everything,
        // so the responder must offer every stored announce.
        manager.handleRequestSync(
            fromPeerID = "aaaabbbbccccdddd",
            request = RequestSyncPacket(p = 7, m = 1, data = ByteArray(0)),
        )
        assertEquals(1000, delegate.unicasts.size)
        assertEquals(setOf("aaaabbbbccccdddd"), delegate.unicasts.map { it.first }.toSet())
        assertEquals(
            "every offered packet is an ANNOUNCE",
            setOf(MessageType.ANNOUNCE.value),
            delegate.unicasts.map { it.second.type }.toSet(),
        )
    }

    @Test
    fun safetyCeilingEvictsLeastRecentlyAnnouncedOnly() = runTest {
        val delegate = RecordingDelegate()
        val manager = manager(delegate, config(announceCap = 100))

        repeat(150) { manager.onPublicPacketSeen(announceFrom(it)) }
        assertEquals(100, manager.storedAnnouncementCount())

        // The survivors are the 100 most recently announced (LRU eviction).
        manager.handleRequestSync(
            fromPeerID = "aaaabbbbccccdddd",
            request = RequestSyncPacket(p = 7, m = 1, data = ByteArray(0)),
        )
        val offeredIndexes = delegate.unicasts.map {
            (it.second.payload[0].toInt() and 0xFF) or ((it.second.payload[1].toInt() and 0xFF) shl 8)
        }.toSet()
        assertEquals((50 until 150).toSet(), offeredIndexes)
    }

    @Test
    fun reAnnouncingPeerRefreshesLruRecency() = runTest {
        val delegate = RecordingDelegate()
        val manager = manager(delegate, config(announceCap = 100))

        repeat(100) { manager.onPublicPacketSeen(announceFrom(it)) }
        // Peer 0 announces again: it must move to the LRU tail...
        manager.onPublicPacketSeen(announceFrom(0))
        // ...so the next insertion over capacity evicts peer 1, not peer 0.
        manager.onPublicPacketSeen(announceFrom(100))
        assertEquals(100, manager.storedAnnouncementCount())

        manager.handleRequestSync(
            fromPeerID = "aaaabbbbccccdddd",
            request = RequestSyncPacket(p = 7, m = 1, data = ByteArray(0)),
        )
        val offeredIndexes = delegate.unicasts.map {
            (it.second.payload[0].toInt() and 0xFF) or ((it.second.payload[1].toInt() and 0xFF) shl 8)
        }.toSet()
        assertEquals(((0..0) + (2..100)).toSet(), offeredIndexes)
    }
}
