package com.app.transport.mesh

import com.app.transport.model.RoutedPacket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Link-level event on a bearer medium. Addresses are bearer-specific link addresses
 * (BLE MAC, Wi-Fi Aware peer handle …) — peerID binding happens separately via
 * [MeshBearer.bindPeer] once the engine identifies the peer behind a link.
 */
sealed interface BearerEvent {
    data class LinkConnected(val linkAddress: String) : BearerEvent
    data class LinkDisconnected(val linkAddress: String) : BearerEvent
    data class RssiChanged(val linkAddress: String, val rssi: Int) : BearerEvent
}

/**
 * SPI for a single-medium transport channel in the mesh network.
 *
 * Adding a new physical medium (BLE, Wi-Fi Aware, TCP relay …) means:
 *   1. Implementing this interface.
 *   2. Registering the class via Metro `@Binds @IntoSet` — no other changes required (OCP).
 *
 * [MeshNetwork] consumes a `Set<MeshBearer>` and provides the unified
 * broadcast / unicast / incoming API that [BluetoothMeshService] talks to.
 */
interface MeshBearer {
    /** Unique identity for this bearer channel. */
    val id: BearerId

    /** Open the underlying transport. Returns true if startup succeeded. */
    fun start(): Boolean

    /** Close the underlying transport gracefully. */
    fun stop()

    /** Deliver [packet] to every connected neighbor on this medium. */
    fun broadcast(packet: RoutedPacket)

    /**
     * Deliver [packet] to a specific peer by [peerID].
     * Returns true if the packet was handed off to the transport.
     */
    fun sendToPeer(peerID: String, packet: RoutedPacket): Boolean

    /** Cancel an in-progress transfer identified by [transferId]. */
    fun cancelTransfer(transferId: String): Boolean

    /**
     * Hot stream of packets received on this medium.
     *
     * Consumers (e.g. [MeshNetwork]) merge this flow with other bearers'
     * flows and route each packet through the engine pipeline.
     * This flow never completes; it stays active for the lifetime of the bearer.
     */
    val incoming: Flow<RoutedPacket>

    /**
     * Live set of peers currently reachable through this medium.
     *
     * [MeshNetwork] consults [PeerLink.peerID] to decide which bearer
     * can unicast to a given destination.
     */
    val neighbors: StateFlow<Set<PeerLink>>

    /**
     * Bind [linkAddress] to a logical [peerID]. The ENGINE decides when a link is a
     * direct neighbor (announce received with max TTL) and calls this; the BEARER owns
     * the address↔peer map. Bearers must ignore link addresses they do not track.
     */
    fun bindPeer(peerID: String, linkAddress: String)

    /**
     * Hot stream of link-level events on this medium (connect / disconnect / RSSI).
     * Replaces the legacy delegate callbacks that leaked platform types (e.g.
     * android.bluetooth.BluetoothDevice) out of the bearer.
     */
    val events: Flow<BearerEvent>
}
