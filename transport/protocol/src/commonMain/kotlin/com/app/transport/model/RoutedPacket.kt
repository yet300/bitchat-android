package com.app.transport.model

import com.app.transport.protocol.BitchatPacket

/**
 * Represents a routed packet with additional metadata
 * Used for processing and routing packets in the mesh network
 */
data class RoutedPacket(
    val packet: BitchatPacket,
    val peerID: String? = null,           // Who sent it (parsed from packet.senderID)
    val relayAddress: String? = null,     // Address it came from (for avoiding loopback)
    val transferId: String? = null,       // Optional stable transfer ID for progress tracking
    // Queued directed send target: when set, the send-queue consumer delivers this frame
    // only to the named peer's link (broadcast fallback if the link is gone) at relay/bulk
    // priority. Local routing metadata only — never serialized to the wire. Used for
    // solicited gossip-sync responses, which must not bypass the bounded priority queue.
    val directedPeerID: String? = null,
)
