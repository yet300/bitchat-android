package com.app.data.mesh

import android.util.Log
import com.app.transport.mesh.MeshBearer
import com.app.transport.mesh.PeerLink
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
 * [BluetoothMeshService] will be refactored in Phase C to use [MeshNetwork]
 * instead of calling [com.app.transport.mesh.BleBearer] directly.
 */
@SingleIn(AppScope::class)
@Inject
internal class MeshNetwork(
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
     * Consumers (e.g. a future PacketProcessor integration) should collect
     * this flow for the lifetime of the component.
     */
    val incoming: Flow<RoutedPacket> = bearers.map { it.incoming }.merge()

    // -----------------------------------------------------------------
    // Outgoing
    // -----------------------------------------------------------------

    /** Start all registered bearers. */
    fun startAll() {
        bearers.forEach { bearer ->
            val ok = bearer.start()
            Log.d(TAG, "Bearer ${bearer.id.id} start → $ok")
        }
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

    /** Cancel an in-progress transfer on the bearer that owns [transferId]. */
    fun cancelTransfer(transferId: String): Boolean =
        bearers.any { it.cancelTransfer(transferId) }

    // -----------------------------------------------------------------
    // Diagnostics
    // -----------------------------------------------------------------

    /** All peers currently reachable across ALL bearers, de-duplicated by peerID. */
    val allNeighbors: Set<PeerLink>
        get() = bearers.flatMap { it.neighbors.value }.distinctBy { it.peerID }.toSet()
}
