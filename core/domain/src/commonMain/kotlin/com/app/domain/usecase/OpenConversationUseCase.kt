package com.app.domain.usecase

import com.app.domain.model.ConversationId
import com.app.domain.model.PeerId
import com.app.domain.repository.NoiseSessionPort

/**
 * Ensures a Noise session exists when a private DM is opened (parity with the legacy
 * `PrivateChatManager.startPrivateChat` -> `establishNoiseSessionIfNeeded`).
 *
 * Only mesh-ephemeral peers have a live BLE Noise session to establish; stable-Noise-key and Nostr
 * DMs are reached over relays and need no handshake. When no session exists, the lexicographically
 * smaller peer id initiates; the larger one announces itself first (to prompt the peer) and then
 * also initiates — mirroring the legacy tie-break.
 */
class OpenConversationUseCase(
    private val session: NoiseSessionPort,
) {
    operator fun invoke(conversationId: ConversationId) {
        val peer = (conversationId as? ConversationId.Private)?.peer ?: return
        if (peer.kind != PeerId.Kind.MESH_EPHEMERAL) return
        if (session.hasSession(peer)) return
        if (session.myPeerId() >= peer.raw) session.announceTo(peer)
        session.initiateHandshake(peer)
    }
}
