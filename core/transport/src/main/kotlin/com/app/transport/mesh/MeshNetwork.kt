package com.app.transport.mesh

import com.app.common.utils.Log
import com.app.transport.MeshConstants
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.sync.PacketIdUtil
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import java.util.concurrent.ConcurrentHashMap

/** Outcome of [MeshNetwork.sendToPeer] — honest about what actually happened. */
enum class SendPath {
    /** A bearer with a bound link to the peer accepted the packet. */
    Direct,

    /** No bearer claims the peer; the packet was flooded on all bearers as fallback. */
    Flooded,

    /** No bearer claims the peer and no bearer accepted the broadcast. */
    NoRoute,
}

/**
 * Multiplexing layer that distributes send operations across all registered [MeshBearer]
 * implementations and merges their incoming flows into a single stream.
 *
 * New transport media (Wi-Fi Aware, TCP relay …) are added by:
 *   1. Implementing [MeshBearer].
 *   2. Registering via Metro `@Binds @IntoSet` — no changes here (OCP).
 *
 * Lives in :core:transport (per MIGRATION_PLAN §6 it is a transport-layer entity) so that
 * [BluetoothMeshService] can consume it as its single data path.
 */
@SingleIn(AppScope::class)
@Inject
class MeshNetwork(
    private val bearers: Set<MeshBearer>,
) {
    companion object {
        private const val TAG = "MeshNetwork"

        /** Capacity of the cross-bearer duplicate-suppression LRU. */
        private const val SEEN_CAPACITY = 512
    }

    // -----------------------------------------------------------------
    // Incoming: merge all bearer flows into one, drop cross-bearer dups
    // -----------------------------------------------------------------

    // LRU of recently seen packet ids. Lightweight pre-engine filter only: the same packet
    // arriving over two bearers (or echoed back by a neighbor) is dropped here;
    // SecurityManager.validatePacket remains the authoritative duplicate/replay check.
    private val seenIds = LinkedHashSet<String>()
    private val seenLock = Any()

    private fun firstSeen(id: String): Boolean = synchronized(seenLock) {
        if (!seenIds.add(id)) {
            // Refresh recency so a busy duplicate keeps getting suppressed
            seenIds.remove(id)
            seenIds.add(id)
            return false
        }
        if (seenIds.size > SEEN_CAPACITY) {
            val iterator = seenIds.iterator()
            iterator.next()
            iterator.remove()
        }
        true
    }

    /**
     * Merged stream of packets arriving on ANY registered bearer, with cross-bearer
     * duplicates (same [PacketIdUtil] id) suppressed.
     *
     * Exception: ANNOUNCE packets at max TTL always pass. The packet id is TTL-blind, so a
     * relayed announce copy arriving first would otherwise suppress the direct copy — and
     * SecurityManager deliberately accepts that duplicate because the engine binds the
     * direct link (bindPeer) off the first max-TTL announce seen on it.
     *
     * Consumers (e.g. [BluetoothMeshService]'s packet pipeline) should collect
     * this flow for the lifetime of the component.
     */
    val incoming: Flow<RoutedPacket> = bearers.map { it.incoming }.merge()
        .filter { routed -> isDirectAnnounce(routed.packet) || firstSeen(PacketIdUtil.computeIdHex(routed.packet)) }

    private fun isDirectAnnounce(packet: BitchatPacket): Boolean =
        packet.type == MessageType.ANNOUNCE.value && packet.ttl >= MeshConstants.MESSAGE_TTL_HOPS

    /** Merged stream of link-level events from ALL bearers. */
    val events: Flow<BearerEvent> = bearers.map { it.events }.merge()

    // -----------------------------------------------------------------
    // Outgoing
    // -----------------------------------------------------------------

    // Bearers whose start() succeeded — outgoing traffic and the NoRoute verdict only
    // consider these (a registered-but-unstarted stub must not fake a Flooded outcome).
    private val started = ConcurrentHashMap.newKeySet<BearerId>()

    /** Start all registered bearers. Returns true if at least one bearer started. */
    fun startAll(): Boolean {
        bearers.forEach { bearer ->
            val ok = bearer.start()
            if (ok) started.add(bearer.id) else started.remove(bearer.id)
            Log.d(TAG, "Bearer ${bearer.id.id} start → $ok")
        }
        return started.isNotEmpty()
    }

    /** Stop all registered bearers gracefully. */
    fun stopAll() {
        bearers.forEach { it.stop() }
        started.clear()
    }

    private fun startedBearers(): List<MeshBearer> = bearers.filter { it.id in started }

    /**
     * Broadcast [packet] on all STARTED bearers (flood to every live medium).
     */
    fun broadcast(packet: RoutedPacket) = startedBearers().forEach { it.broadcast(packet) }

    /**
     * Send [packet] to [peerID] via the first started bearer that lists the peer in its
     * [MeshBearer.neighbors]; falls back to flooding all started bearers otherwise.
     * The returned [SendPath] is honest about which of the three actually happened:
     * [SendPath.NoRoute] means no STARTED bearer accepted the packet — a registered but
     * never-started bearer (e.g. the Wi-Fi Aware stub) does not count.
     */
    fun sendToPeer(peerID: String, packet: RoutedPacket): SendPath {
        val active = startedBearers()
        val primary = active.firstOrNull { bearer ->
            bearer.neighbors.value.any { it.peerID == peerID }
        }
        if (primary != null && primary.sendToPeer(peerID, packet)) {
            return SendPath.Direct
        }
        if (active.isEmpty()) {
            Log.w(TAG, "No started bearers; cannot deliver to $peerID")
            return SendPath.NoRoute
        }
        Log.d(TAG, "No direct link to $peerID; flooding as fallback")
        active.forEach { it.broadcast(packet) }
        return SendPath.Flooded
    }

    /**
     * Bind [linkAddress] to [peerID] on whichever bearer tracks that link.
     * Bearers ignore addresses they do not own ([MeshBearer.bindPeer] contract).
     */
    fun bindPeer(peerID: String, linkAddress: String) =
        bearers.forEach { it.bindPeer(peerID, linkAddress) }

    /** Cancel an in-progress transfer on ALL bearers that own [transferId]. */
    fun cancelTransfer(transferId: String): Boolean =
        bearers.fold(false) { cancelled, bearer -> bearer.cancelTransfer(transferId) || cancelled }

    // -----------------------------------------------------------------
    // Diagnostics
    // -----------------------------------------------------------------

    /** All peers currently reachable across ALL bearers, de-duplicated by peerID. */
    val allNeighbors: Set<PeerLink>
        get() = bearers.flatMap { it.neighbors.value }.distinctBy { it.peerID }.toSet()
}
