@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.app.transport.sync

import com.app.common.AppDispatchers
import com.app.transport.model.RequestSyncPacket
import com.app.transport.model.SyncTypeFlags
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock

/**
 * SYNC_SCALE P3-emit pins: our REQUEST_SYNC now carries 0x04 types=publicMessages and,
 * when the GCS filter cannot cover the whole store, an exact covered-prefix 0x05
 * since-cursor (iOS GossipSyncManager.swift:558-595). The responder honors an incoming
 * cursor for messages; announces stay cursor-exempt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GossipSyncManagerTypesSinceTest {
    private val initialSyncTypes = SyncTypeFlags.publicMessages
        .union(SyncTypeFlags.groupMessage)
        .union(SyncTypeFlags.fragment)
        .union(SyncTypeFlags.fileTransfer)
        .union(SyncTypeFlags.prekeyBundle)
        .union(SyncTypeFlags.boardPost)

    private class RecordingDelegate : GossipSyncManager.Delegate {
        val broadcasts = mutableListOf<BitchatPacket>()
        val unicasts = mutableListOf<Pair<String, BitchatPacket>>()
        override fun sendPacket(packet: BitchatPacket) { broadcasts.add(packet) }
        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
            unicasts.add(peerID to packet)
        }
        override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket = packet
    }

    private fun config(maxBytes: Int = 400, seenCap: Int = 500) =
        object : GossipSyncManager.ConfigProvider {
            override fun seenCapacity(): Int = seenCap
            override fun gcsMaxBytes(): Int = maxBytes
            override fun gcsTargetFpr(): Double = 0.01
        }

    private fun TestScope.manager(
        delegate: RecordingDelegate,
        configProvider: GossipSyncManager.ConfigProvider = config(),
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

    private fun announce(index: Int): BitchatPacket = BitchatPacket(
        type = MessageType.ANNOUNCE.value,
        senderID = ByteArray(8) { if (it == 7) index.toByte() else 0x0C },
        timestamp = Clock.System.now().toEpochMilliseconds().toULong(),
        payload = byteArrayOf(0x7E, index.toByte()),
        ttl = 7u,
    )

    private fun message(index: Int, timestamp: Long): BitchatPacket = BitchatPacket(
        type = MessageType.MESSAGE.value,
        senderID = ByteArray(8) { 0x0D },
        recipientID = null, // broadcast
        timestamp = timestamp.toULong(),
        payload = byteArrayOf(0x11, (index and 0xFF).toByte(), ((index ushr 8) and 0xFF).toByte()),
        ttl = 7u,
    )

    private val emptyFilter = RequestSyncPacket(p = 7, m = 1, data = ByteArray(0))

    @Test
    fun requestCarriesPublicMessagesTypesAndNoCursorWhenStoreFits() = runTest {
        val delegate = RecordingDelegate()
        val manager = manager(delegate)
        repeat(3) { manager.onPublicPacketSeen(announce(it)) }

        manager.scheduleInitialSyncToPeer("aaaabbbbccccdddd", 100)
        advanceTimeBy(1_000); runCurrent()

        assertEquals(1, delegate.unicasts.size)
        val req = RequestSyncPacket.decode(delegate.unicasts[0].second.payload)
        assertNotNull(req)
        assertEquals(initialSyncTypes, req!!.types)
        assertNull("small store fully covered -> no cursor", req.sinceTimestamp)
    }

    @Test
    fun requestCarriesExactCoveredPrefixCursorWhenFilterCannotCoverStore() = runTest {
        // Tiny filter budget: nMax = 40*8/(7+2) = 35 < 100 candidates -> cursor required.
        val delegate = RecordingDelegate()
        val manager = manager(delegate, config(maxBytes = 40))
        val baseTs = 5_000_000L
        val timestamps = (0 until 100).map { baseTs + it * 1000L }
        timestamps.forEachIndexed { i, ts -> manager.onPublicPacketSeen(message(i, ts)) }

        manager.scheduleInitialSyncToPeer("aaaabbbbccccdddd", 100)
        advanceTimeBy(1_000); runCurrent()

        assertEquals(1, delegate.unicasts.size)
        val req = RequestSyncPacket.decode(delegate.unicasts[0].second.payload)
        assertNotNull(req)
        assertEquals(initialSyncTypes, req!!.types)
        val since = req.sinceTimestamp
        assertNotNull("filter cannot cover 100 candidates -> cursor must be set", since)

        // Replicate the covered-prefix computation with the same deterministic inputs:
        // candidates sorted newest-first, takeN = min(nMax, cap, size), cursor =
        // included[includedCount-1].timestamp.
        val sortedDesc = timestamps.sortedDescending()
        val p = GCSFilter.deriveP(0.01)
        val nMax = GCSFilter.estimateMaxElementsForSize(40, p)
        val takeN = minOf(nMax, 500, 100)
        val includedTs = sortedDesc.take(takeN)
        // ids must match production order: rebuild them from the same packets newest-first
        val idsNewestFirst = timestamps.withIndex()
            .sortedByDescending { it.value }
            .take(takeN)
            .map { (i, ts) -> PacketIdUtil.computeIdBytes(message(i, ts)) }
        val params = GCSFilter.buildFilter(idsNewestFirst, 40, 0.01)
        assertTrue(params.includedCount in 1..takeN)
        assertEquals(includedTs[params.includedCount - 1], since)
    }

    @Test
    fun responderHonorsSinceCursorForMessagesButNotAnnounces() = runTest {
        val delegate = RecordingDelegate()
        val manager = manager(delegate)
        manager.onPublicPacketSeen(announce(1))
        val baseTs = 5_000_000L
        repeat(10) { manager.onPublicPacketSeen(message(it, baseTs + it * 1000L)) }

        val since = baseTs + 5_000L // messages 0..4 are older than the cursor
        manager.handleRequestSync(
            fromPeerID = "aaaabbbbccccdddd",
            request = RequestSyncPacket(p = 7, m = 1, data = ByteArray(0), sinceTimestamp = since),
        )

        val offered = delegate.unicasts.map { it.second }
        assertEquals(1, offered.count { it.type == MessageType.ANNOUNCE.value })
        val offeredMsgTs = offered.filter { it.type == MessageType.MESSAGE.value }
            .map { it.timestamp.toLong() }.sorted()
        assertEquals((5..9).map { baseTs + it * 1000L }, offeredMsgTs)
    }

    @Test
    fun legacyThreeTlvRequestStillServesAnnouncesAndMessages() = runTest {
        val delegate = RecordingDelegate()
        val manager = manager(delegate)
        manager.onPublicPacketSeen(announce(1))
        manager.onPublicPacketSeen(message(0, 5_000_000L))

        manager.handleRequestSync("aaaabbbbccccdddd", emptyFilter) // types=null, since=null
        assertEquals(2, delegate.unicasts.size)
    }

    @Test
    fun typesOnlyAnnounceSkipsMessages() = runTest {
        val delegate = RecordingDelegate()
        val manager = manager(delegate)
        manager.onPublicPacketSeen(announce(1))
        manager.onPublicPacketSeen(message(0, 5_000_000L))

        manager.handleRequestSync(
            "aaaabbbbccccdddd",
            RequestSyncPacket(p = 7, m = 1, data = ByteArray(0), types = SyncTypeFlags.announce),
        )
        assertEquals(1, delegate.unicasts.size)
        assertEquals(MessageType.ANNOUNCE.value, delegate.unicasts[0].second.type)
    }

    @Test
    fun typedRequestReturnsOnlyTheRequestedArchiveWithSinceCursor() = runTest {
        val delegate = RecordingDelegate()
        val manager = manager(delegate)
        val base = 5_000_000L
        fun group(index: Int) = BitchatPacket(
            type = MessageType.GROUP_MESSAGE.value,
            senderID = ByteArray(8) { 0x21 },
            timestamp = (base + index * 1_000L).toULong(),
            payload = byteArrayOf(index.toByte()),
            ttl = 7u,
        )
        manager.onPublicPacketSeen(group(0))
        manager.onPublicPacketSeen(group(1))
        manager.onPublicPacketSeen(message(1, base + 9_000L))

        manager.handleRequestSync(
            "aaaabbbbccccdddd",
            RequestSyncPacket(7, 1, ByteArray(0), types = SyncTypeFlags.groupMessage, sinceTimestamp = base + 1_000L),
        )

        assertEquals(listOf(MessageType.GROUP_MESSAGE.value), delegate.unicasts.map { it.second.type })
        assertEquals(base + 1_000L, delegate.unicasts.single().second.timestamp.toLong())
    }
}
