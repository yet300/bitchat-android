package com.app.transport.mesh

import android.util.Log
import com.app.transport.model.RoutedPacket
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

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
    }

    // -----------------------------------------------------------------
    // Incoming: merge all bearer flows into one
    // -----------------------------------------------------------------

    /**
     * Merged stream of packets arriving on ANY registered bearer.
     *
     * Consumers (e.g. [BluetoothMeshService]'s packet pipeline) should collect
     * this flow for the lifetime of the component.
     */
    val incoming: Flow<RoutedPacket> = bearers.map { it.incoming }.merge()

    /** Merged stream of link-level events from ALL bearers. */
    val events: Flow<BearerEvent> = bearers.map { it.events }.merge()

    // -----------------------------------------------------------------
    // Outgoing
    // -----------------------------------------------------------------

    /** Start all registered bearers. Returns true if at least one bearer started. */
    fun startAll(): Boolean {
        var anyStarted = false
        bearers.forEach { bearer ->
            val ok = bearer.start()
            anyStarted = anyStarted || ok
            Log.d(TAG, "Bearer ${bearer.id.id} start → $ok")
        }
        return anyStarted
    }

    /** Stop all registered bearers gracefully. */
    fun stopAll() = bearers.forEach { it.stop() }

    /**
     * Broadcast [packet] on ALL bearers (flood to every medium).
     */
    fun broadcast(packet: RoutedPacket) = bearers.forEach { it.broadcast(packet) }

    /**
     * Send [packet] to [peerID] via the first bearer that lists the peer in its
     * [MeshBearer.neighbors].  Falls back to broadcast-on-all if no bearer claims
     * direct reachability.
     *
     * Returns true if at least one bearer accepted the packet.
     */
    fun sendToPeer(peerID: String, packet: RoutedPacket): Boolean {
        val primary = bearers.firstOrNull { bearer ->
            bearer.neighbors.value.any { it.peerID == peerID }
        }
        return if (primary != null) {
            primary.sendToPeer(peerID, packet)
        } else {
            Log.d(TAG, "No bearer claims $peerID; broadcasting as fallback")
            bearers.forEach { it.broadcast(packet) }
            false
        }
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
