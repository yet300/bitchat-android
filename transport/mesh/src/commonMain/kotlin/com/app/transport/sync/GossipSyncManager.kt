@file:OptIn(ExperimentalTime::class)

package com.app.transport.sync

import co.touchlab.stately.collections.ConcurrentMutableMap
import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import com.app.common.AppDispatchers
import com.app.common.encoding.hexEncodedString
import com.app.common.utils.Log
import com.app.transport.MeshTrafficLog
import com.app.transport.model.RequestSyncPacket
import com.app.transport.model.SyncTypeFlags
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.peerIdToRoutingBytes
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.SpecialRecipients
import kotlinx.coroutines.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

/**
 * Gossip-based synchronization manager using on-demand GCS filters.
 * Tracks seen public packets (ANNOUNCE, broadcast MESSAGE) and periodically requests sync
 * from neighbors. Responds to REQUEST_SYNC by sending missing packets.
 */
internal class GossipSyncManager(
    private val myPeerID: String,
    private val scope: CoroutineScope,
    private val configProvider: ConfigProvider,
    private val trafficLog: MeshTrafficLog? = null,
    private val dispatchers: AppDispatchers = AppDispatchers(),
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val requestSyncManager: RequestSyncManager = RequestSyncManager(nowMs = nowMillis),
) {
    /** Exposed so BLE ingress / SecurityManager share the same pending-request window. */
    fun isValidSyncResponse(peerID: String): Boolean =
        requestSyncManager.isValidResponse(peerID = peerID, isRSR = true)
    interface Delegate {
        fun sendPacket(packet: BitchatPacket)
        fun sendPacketToPeer(peerID: String, packet: BitchatPacket)
        fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket
        fun getConnectedPeers(): List<String> = emptyList()
    }

    interface ConfigProvider {
        fun seenCapacity(): Int // max packets we sync per request (cap across types)
        fun gcsMaxBytes(): Int
        fun gcsTargetFpr(): Double // percent -> 0.0..1.0

        /**
         * Safety ceiling for the announce store, independent of [seenCapacity]. The store must
         * hold ~one announce per live peer (iOS caps it only by the 180s liveness prune); this
         * cap exists solely against hostile peer-ID spoofing, applied LRU.
         */
        fun announceCapacity(): Int = SyncDefaults.DEFAULT_ANNOUNCE_CAPACITY
    }

    companion object {
        private const val TAG = "GossipSyncManager"

        // Ignore ANNOUNCE packets older than this (matches the mesh stale-peer timeout, 3 minutes).
        // Inlined to keep sync independent of the mesh layer's AppConstants.
        private const val STALE_ANNOUNCE_MAX_AGE_MS: Long = 180_000L

        // Minimum gap between two REQUEST_SYNCs SENT to the same peer. Every direct
        // announce schedules a per-peer sync, and peers resend cached announces via sync
        // responses — without this floor the two feed each other into a request storm.
        internal const val MIN_PEER_SYNC_INTERVAL_MS: Long = 30_000L
    }

    var delegate: Delegate? = null

    // Defaults (configurable constants)
    private val defaultMaxBytes = SyncDefaults.DEFAULT_FILTER_BYTES
    private val defaultFpr = SyncDefaults.DEFAULT_FPR_PERCENT

    // Stored packets for sync:
    // - broadcast messages: keep up to seenCapacity() most recent, keyed by packetId
    private val messages = LinkedHashMap<String, BitchatPacket>()
    private val messagesLock = Lock()
    // - announcements: only keep latest per sender peerID. Insertion-ordered so the
    //   announceCapacity() safety cap can evict least-recently-announced first (LRU);
    //   the real bound is the 180s pruneStaleAnnouncements liveness sweep.
    private val latestAnnouncementByPeer = LinkedHashMap<String, Pair<String, BitchatPacket>>()
    private val announcementsLock = Lock()

    // Wire-preserving archives for non-public sync types. DAOs hold decoded application state, but
    // a sync response must replay the exact packet originally signed by its author.
    private val typedPackets = mutableMapOf<SyncTypeFlags, LinkedHashMap<String, BitchatPacket>>()
    private val typedPacketsLock = Lock()
    private val lastPeriodicSyncAt = mutableMapOf<SyncTypeFlags, Long>()
    private val periodicTypes = listOf(
        SyncTypeFlags.publicMessages.union(SyncTypeFlags.groupMessage) to 15_000L,
        SyncTypeFlags.fragment to 30_000L,
        SyncTypeFlags.fileTransfer to 60_000L,
        SyncTypeFlags.prekeyBundle to 60_000L,
        SyncTypeFlags.boardPost to 60_000L,
    )

    // Per-peer rate limiting keyed on the last SENT request time, plus a guard against
    // stacking multiple delayed jobs while one is already queued for the peer.
    private val lastSyncRequestSentAt = ConcurrentMutableMap<String, Long>()
    private val pendingPeerSyncs = mutableSetOf<String>()
    private val pendingPeerSyncsLock = Lock()

    // Per-requester response budget (iOS parity: 8 responses / 30 s sliding window).
    // A single response can replay the whole store; this bounds how often one peer
    // can trigger a full diff pass, checked BEFORE any store scan.
    private val responseRateLimiter = SyncResponseRateLimiter()

    private var periodicJob: Job? = null
    private var cleanupJob: Job? = null
    fun start() {
        periodicJob?.cancel()
        periodicJob = scope.launch(dispatchers.io) {
            while (isActive) {
                try {
                    delay(1_000)
                    val now = nowMillis()
                    periodicTypes.forEach { (types, interval) ->
                        val last = lastPeriodicSyncAt[types]
                        if (last == null || now - last >= interval) {
                            lastPeriodicSyncAt[types] = now
                            sendRequestSync(types)
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.e(TAG, "Periodic sync error: ${e.message}") }
            }
        }

        // Start periodic cleanup of stale announcements and messages
        cleanupJob?.cancel()
        cleanupJob = scope.launch(dispatchers.io) {
            while (isActive) {
                try {
                    delay(SyncDefaults.CLEANUP_INTERVAL_MS.milliseconds)
                    pruneStaleAnnouncements()
                    responseRateLimiter.prune(nowMillis())
                    requestSyncManager.cleanup()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.e(TAG, "Periodic cleanup error: ${e.message}") }
            }
        }
    }

    fun stop() {
        periodicJob?.cancel(); periodicJob = null
        cleanupJob?.cancel(); cleanupJob = null
    }

    fun scheduleInitialSync(delayMs: Long = 5_000L) {
        scope.launch(dispatchers.io) {
            delay(delayMs)
            sendRequestSync()
        }
    }

    fun scheduleInitialSyncToPeer(peerID: String, delayMs: Long = 5_000L) {
        // No-op while a request to this peer is younger than the interval or a delayed
        // job is already queued — callers invoke this on every direct announce.
        val last = lastSyncRequestSentAt[peerID]
        if (last != null && nowMillis() - last < MIN_PEER_SYNC_INTERVAL_MS) return
        val queued = pendingPeerSyncsLock.withLock { pendingPeerSyncs.add(peerID) }
        if (!queued) return
        scope.launch(dispatchers.io) {
            try {
                delay(delayMs.milliseconds)
                sendRequestSyncToPeer(peerID)
            } finally {
                pendingPeerSyncsLock.withLock { pendingPeerSyncs.remove(peerID) }
            }
        }
    }

    fun onPublicPacketSeen(packet: BitchatPacket) {
        // Only ANNOUNCE or broadcast MESSAGE
        val mt = MessageType.fromValue(packet.type)
        val isBroadcastMessage = (mt == MessageType.MESSAGE && (packet.recipientID == null || packet.recipientID.contentEquals(SpecialRecipients.BROADCAST)))
        val isAnnouncement = (mt == MessageType.ANNOUNCE)
        val syncType = syncTypeFor(packet)
        if (!isBroadcastMessage && !isAnnouncement && syncType == null) return

        val idBytes = PacketIdUtil.computeIdBytes(packet)
        val id = idBytes.hexEncodedString()

        if (isBroadcastMessage) {
            messagesLock.withLock {
                messages[id] = packet
                // Enforce capacity (remove oldest when exceeded)
                val cap = configProvider.seenCapacity().coerceAtLeast(1)
                while (messages.size > cap) {
                    val it = messages.entries.iterator()
                    if (it.hasNext()) { it.next(); it.remove() } else break
                }
            }
        } else if (isAnnouncement) {
            // Ignore stale announcements older than STALE_PEER_TIMEOUT
            val now = Clock.System.now().toEpochMilliseconds()
            val age = now - packet.timestamp.toLong()
            if (age > STALE_ANNOUNCE_MAX_AGE_MS) {
                Log.d(TAG, "Ignoring stale ANNOUNCE (age=${age}ms > ${STALE_ANNOUNCE_MAX_AGE_MS}ms)")
                return
            }
            // senderID is fixed-size 8 bytes; map to hex string for key
            val sender = packet.senderID.hexEncodedString()
            announcementsLock.withLock {
                // Refresh recency: a live announcing peer moves to the tail so the safety
                // cap only ever evicts the least-recently-announced (spoofed/dead) entries.
                latestAnnouncementByPeer.remove(sender)
                latestAnnouncementByPeer[sender] = id to packet
                val cap = configProvider.announceCapacity().coerceAtLeast(1)
                while (latestAnnouncementByPeer.size > cap) {
                    val it = latestAnnouncementByPeer.entries.iterator()
                    if (it.hasNext()) { it.next(); it.remove() } else break
                }
            }
        } else if (syncType != null) {
            typedPacketsLock.withLock {
                val packets = typedPackets.getOrPut(syncType) { LinkedHashMap() }
                packets[id] = packet
                val cap = configProvider.seenCapacity().coerceAtLeast(1)
                while (packets.size > cap) {
                    val iterator = packets.entries.iterator()
                    if (iterator.hasNext()) { iterator.next(); iterator.remove() } else break
                }
            }
        }
    }

    private fun sendRequestSync(types: SyncTypeFlags = SyncTypeFlags.publicMessages) {
        // Prefer unicast to connected peers so RSR can be attributed (iOS sendPeriodicSync).
        val connected = delegate?.getConnectedPeers().orEmpty()
        if (connected.isNotEmpty()) {
            for (peerID in connected) {
                sendRequestSyncToPeer(peerID, types)
            }
            return
        }
        // Discovery fallback: broadcast without solicitation registry (no peer to attribute).
        val payload = buildGcsPayload(types)
        val packet = BitchatPacket(
            type = MessageType.REQUEST_SYNC.value,
            senderID = peerIdToRoutingBytes(myPeerID),
            timestamp = Clock.System.now().toEpochMilliseconds().toULong(),
            payload = payload,
            ttl = SyncDefaults.SYNC_TTL_HOPS // neighbors only
        )
        val signed = delegate?.signPacketForBroadcast(packet) ?: packet
        delegate?.sendPacket(signed)
    }

    private fun sendRequestSyncToPeer(
        peerID: String,
        types: SyncTypeFlags = allSyncTypes(),
    ) {
        // Re-check at send time: the delayed job may fire after another path (or a
        // previous job) already sent a request to this peer within the interval.
        val now = nowMillis()
        val last = lastSyncRequestSentAt[peerID]
        if (last != null && now - last < MIN_PEER_SYNC_INTERVAL_MS) {
            Log.d(TAG, "Skipping sync request to $peerID (rate-limited, last sent ${now - last}ms ago)")
            return
        }
        lastSyncRequestSentAt[peerID] = now
        // Register solicitation so inbound RSR from this peer passes the ingress gate.
        requestSyncManager.registerRequest(peerID)

        val payload = buildGcsPayload(types)

        val packet = BitchatPacket(
            type = MessageType.REQUEST_SYNC.value,
            senderID = peerIdToRoutingBytes(myPeerID),
            recipientID = peerIdToRoutingBytes(peerID),
            timestamp = Clock.System.now().toEpochMilliseconds().toULong(),
            payload = payload,
            ttl = SyncDefaults.SYNC_TTL_HOPS // neighbor only
        )
        Log.d(TAG, "Sending sync request to $peerID (${payload.size} bytes)")
        // Sign and send directly to peer
        val signed = delegate?.signPacketForBroadcast(packet) ?: packet
        delegate?.sendPacketToPeer(peerID, signed)
    }

    fun handleRequestSync(fromPeerID: String, request: RequestSyncPacket) {
        // Response budget gate FIRST — before any GCS decode or store scan, so a
        // request flood costs the responder nothing but this check.
        if (!responseRateLimiter.shouldRespond(fromPeerID, nowMillis())) {
            Log.w(TAG, "Rate-limited REQUEST_SYNC from $fromPeerID (max ${SyncResponseRateLimiter.MAX_RESPONSES}/${SyncResponseRateLimiter.WINDOW_MILLIS}ms)")
            trafficLog?.onRateLimitDrop("syncResponse")
            return
        }
        // iOS parity: an absent 0x04 TLV means announce+message (legacy 3-TLV requesters).
        val requestedTypes = request.types ?: SyncTypeFlags.publicMessages
        // The requester's filter only covers packets at or after this cursor; older
        // packets are outside the filter but NOT missing — without the cursor we would
        // re-send that entire tail every round.
        val since = request.sinceTimestamp

        // Decode GCS into sorted set for membership checks
        val sorted = GCSFilter.decodeToSortedSet(request.p, request.m, request.data)
        fun mightContain(id: ByteArray): Boolean {
            val v = GCSFilter.h64(id) % request.m
            val nonZeroV = if (v == 0L) 1L else v
            return GCSFilter.contains(sorted, nonZeroV)
        }

        // 1) Announcements: send latest per peerID if remote doesn't have them.
        // Cursor-exempt (iOS parity): they carry the signing keys needed to verify
        // everything else and there is at most one per peer, so resend cost is bounded.
        if (requestedTypes.contains(SyncTypeFlags.announce)) {
            val announces = announcementsLock.withLock { latestAnnouncementByPeer.values.toList() }
            for (pair in announces) {
                val (id, pkt) = pair
                val idBytes = hexToBytes(id)
                if (!mightContain(idBytes)) {
                    // Send original packet unchanged to requester only (keep local TTL)
                    // Mark as solicited response (iOS GossipSyncManager isRSR = true).
                    val toSend = pkt.copy(ttl = SyncDefaults.SYNC_TTL_HOPS, isRSR = true)
                    delegate?.sendPacketToPeer(fromPeerID, toSend)
                    Log.d(TAG, "Sent sync announce: Type ${toSend.type} from ${toSend.senderID.hexEncodedString()} to $fromPeerID packet id ${idBytes.hexEncodedString()}")
                }
            }
        }

        // 2) Broadcast messages: send all they lack that the cursor says they cover
        if (requestedTypes.contains(SyncTypeFlags.message)) {
            val toSendMsgs = messagesLock.withLock { messages.values.toList() }
            for (pkt in toSendMsgs) {
                if (since != null && pkt.timestamp.toLong() < since) continue
                val idBytes = PacketIdUtil.computeIdBytes(pkt)
                if (!mightContain(idBytes)) {
                    val toSend = pkt.copy(ttl = SyncDefaults.SYNC_TTL_HOPS, isRSR = true)
                    delegate?.sendPacketToPeer(fromPeerID, toSend)
                    Log.d(TAG, "Sent sync message: Type ${toSend.type} to $fromPeerID packet id ${idBytes.hexEncodedString()}")
                }
            }
        }

        // Every non-public archive shares the same GCS/missing-packet and since-cursor semantics.
        // The packets are replayed unchanged except for the neighbor-only response TTL.
        typedPacketsLock.withLock {
            typedPackets.forEach { (type, packets) ->
                if (!requestedTypes.contains(type)) return@forEach
                packets.values.forEach { pkt ->
                    if (since != null && pkt.timestamp.toLong() < since) return@forEach
                    val idBytes = PacketIdUtil.computeIdBytes(pkt)
                    if (!mightContain(idBytes)) {
                        delegate?.sendPacketToPeer(
                            fromPeerID,
                            pkt.copy(ttl = SyncDefaults.SYNC_TTL_HOPS, isRSR = true),
                        )
                    }
                }
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 == 0) hex else "0$hex"
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            out[i/2] = clean.substring(i, i+2).toInt(16).toByte()
            i += 2
        }
        return out
    }

    private fun buildGcsPayload(types: SyncTypeFlags = SyncTypeFlags.publicMessages): ByteArray {
        // Collect candidates: latest announcement per peer + recent broadcast messages
        val list = ArrayList<BitchatPacket>()
        // announcements
        if (types.contains(SyncTypeFlags.announce)) announcementsLock.withLock {
            for ((_, pair) in latestAnnouncementByPeer) list.add(pair.second)
        }
        // messages
        if (types.contains(SyncTypeFlags.message)) messagesLock.withLock { list.addAll(messages.values) }
        typedPacketsLock.withLock {
            typedPackets.forEach { (type, packets) -> if (types.contains(type)) list.addAll(packets.values) }
        }
        // sort by timestamp desc, then take up to min(seenCapacity, fit capacity)
        list.sortByDescending { it.timestamp.toLong() }

        val maxBytes = try { configProvider.gcsMaxBytes() } catch (_: Exception) { defaultMaxBytes }
        val fpr = try { configProvider.gcsTargetFpr() } catch (_: Exception) { defaultFpr }
        val p = GCSFilter.deriveP(fpr)
        val nMax = GCSFilter.estimateMaxElementsForSize(maxBytes, p)
        val cap = configProvider.seenCapacity().coerceAtLeast(1)
        val takeN = minOf(nMax, cap, list.size)
        if (takeN <= 0) {
            val p0 = GCSFilter.deriveP(fpr)
            return RequestSyncPacket(
                p = p0, m = 1, data = ByteArray(0),
                types = types,
            ).encode()
        }
        val included = list.take(takeN)
        val ids = included.map { pkt -> PacketIdUtil.computeIdBytes(pkt) }
        val params = GCSFilter.buildFilter(ids, maxBytes, fpr)
        val mVal = if (params.m <= 0L) 1 else params.m
        // Covered-prefix cursor (iOS GossipSyncManager.swift:558-595): when the filter
        // can't represent every candidate — takeN < store size, or the encoder trimmed
        // the tail to fit the byte budget — tell the responder how far back the filter
        // actually reaches. Candidates are newest-first, so the covered set is a
        // contiguous newest-prefix and the oldest included timestamp is an exact cursor.
        val covered = params.includedCount
        val sinceTimestamp: Long? =
            if (covered < list.size && covered > 0) included[covered - 1].timestamp.toLong() else null
        return RequestSyncPacket(
            p = params.p, m = mVal, data = params.data,
            types = types,
            sinceTimestamp = sinceTimestamp,
        ).encode()
    }

    private fun syncTypeFor(packet: BitchatPacket): SyncTypeFlags? = when (MessageType.fromValue(packet.type)) {
        MessageType.FRAGMENT -> SyncTypeFlags.fragment
        MessageType.FILE_TRANSFER -> SyncTypeFlags.fileTransfer
        MessageType.BOARD_POST -> SyncTypeFlags.boardPost
        MessageType.PREKEY_BUNDLE -> SyncTypeFlags.prekeyBundle
        MessageType.GROUP_MESSAGE -> SyncTypeFlags.groupMessage
        else -> null
    }

    private fun allSyncTypes(): SyncTypeFlags = periodicTypes.fold(SyncTypeFlags.publicMessages) { acc, entry -> acc.union(entry.first) }

    // Periodically remove stale announcements and all their messages
    private fun pruneStaleAnnouncements() {
        val now = Clock.System.now().toEpochMilliseconds()
        val stalePeers = mutableListOf<String>()

        // Identify stale announcements by age (snapshot: removal below re-acquires the lock)
        val entries = announcementsLock.withLock { latestAnnouncementByPeer.entries.map { it.key to it.value } }
        for ((peerID, pair) in entries) {
            val pkt = pair.second
            val age = now - pkt.timestamp.toLong()
            if (age > STALE_ANNOUNCE_MAX_AGE_MS) {
                stalePeers.add(peerID)
            }
        }

        if (stalePeers.isEmpty()) return

        // Remove announcements and their messages
        var totalPrunedMsgs = 0
        for (peerID in stalePeers) {
            // Count messages to be pruned for logging
            val toRemove = mutableListOf<String>()
            messagesLock.withLock {
                for ((id, message) in messages) {
                    val sender = message.senderID.hexEncodedString()
                    if (sender == peerID) toRemove.add(id)
                }
            }
            totalPrunedMsgs += toRemove.size

            // Reuse existing removal which also clears announcement entry
            removeAnnouncementForPeer(peerID)
        }

        Log.d(TAG, "Pruned ${stalePeers.size} stale announcements and $totalPrunedMsgs messages")
    }

    // Test seam: current size of the announce store (convergence tests assert no eviction).
    internal fun storedAnnouncementCount(): Int =
        announcementsLock.withLock { latestAnnouncementByPeer.size }

    // Explicitly remove stored announcement for a given peer (hex ID)
    fun removeAnnouncementForPeer(peerID: String) {
        val key = peerID.lowercase()
        // Forget rate-limit state so a peer that leaves and reconnects gets its
        // initial sync without waiting out the interval.
        lastSyncRequestSentAt.remove(peerID)
        lastSyncRequestSentAt.remove(key)
        if (announcementsLock.withLock { latestAnnouncementByPeer.remove(key) } != null) {
            Log.d(TAG, "Removed stored announcement for peer $peerID")
        }

        // Collect IDs to remove first to avoid modifying collection while iterating
        val idsToRemove = mutableListOf<String>()
        messagesLock.withLock {
            for ((id, message) in messages) {
                val sender = message.senderID.hexEncodedString()
                if (sender == key) {
                    idsToRemove.add(id)
                }
            }
        }
        
        // Now remove the collected IDs
        messagesLock.withLock {
            for (id in idsToRemove) {
                messages.remove(id)
            }
        }
        
        if (idsToRemove.isNotEmpty()) {
            Log.d(TAG, "Pruned ${idsToRemove.size} messages with senders without announcements")
        }
    }
}
