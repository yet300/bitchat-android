package com.app.transport.model

import com.app.transport.protocol.BitchatPacket

/**
 * Represents a routed packet with additional metadata used for processing and routing
 * in the mesh network.
 *
 * Identity split (iOS BLEIngressLinkRegistry parity):
 * - [peerID] is the **logical origin** used for signature/Noise/session handling
 *   ([BleIngressPacketContext.validationPeerID] / packet.senderID for normal traffic).
 * - [relayAddress] is the local link the frame arrived on (loop avoidance / rebind).
 * - [previousHopPeerID] is the peer bound to that link when known (direct neighbor);
 *   for multi-hop this differs from [peerID] and must not be used for crypto.
 */
data class RoutedPacket(
    val packet: BitchatPacket,
    /** Logical origin peer (crypto/handlers). Not necessarily the previous radio hop. */
    val peerID: String? = null,
    /** Opaque local link address this frame arrived on / will leave on. */
    val relayAddress: String? = null,
    val transferId: String? = null,
    // Queued directed send target: when set, the send-queue consumer delivers this frame
    // only to the named peer's link (broadcast fallback if the link is gone) at relay/bulk
    // priority. Local routing metadata only — never serialized to the wire. Used for
    // solicited gossip-sync responses, which must not bypass the bounded priority queue.
    val directedPeerID: String? = null,
    /**
     * Peer ID bound to [relayAddress] at ingress (previous hop), when known.
     * Local metadata only — never on the wire. Null for locally originated packets.
     */
    val previousHopPeerID: String? = null,
)
