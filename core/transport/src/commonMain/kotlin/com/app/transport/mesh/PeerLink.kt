package com.app.transport.mesh

/**
 * A peer reachable through a specific [MeshBearer].
 *
 * [peerID] is the logical 16-hex-char Noise fingerprint used everywhere in the routing layer.
 * [deviceAddress] is the bearer-specific link-layer address (BLE MAC, Wi-Fi Aware peer handle, …).
 * [isInbound] is true when the peer initiated the connection.
 * [rssi] is the most-recent signal strength reading, if available.
 */
data class PeerLink(
    val peerID: String,
    val deviceAddress: String,
    val isInbound: Boolean,
    val rssi: Int? = null,
)
