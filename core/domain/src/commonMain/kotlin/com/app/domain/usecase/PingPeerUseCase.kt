package com.app.domain.usecase

import com.app.domain.model.MeshPingProbe
import com.app.domain.model.PeerId
import com.app.domain.repository.DebugRepository

/**
 * Measures round-trip time and hop count to a mesh peer. Business rule: an echo probe is addressed
 * by the ephemeral mesh peerID that the packet's recipientID field carries. A Nostr alias has no
 * mesh route, and a stable Noise key is not a routing address, so neither can be probed.
 */
class PingPeerUseCase(
    private val debug: DebugRepository,
) {
    suspend operator fun invoke(peer: PeerId): MeshPingProbe? {
        if (peer.kind != PeerId.Kind.MESH_EPHEMERAL) return null
        return debug.pingPeer(peer.raw)
    }
}
