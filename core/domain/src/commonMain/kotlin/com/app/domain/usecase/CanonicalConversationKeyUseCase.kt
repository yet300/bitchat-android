package com.app.domain.usecase

import com.app.domain.model.ConversationId
import com.app.domain.model.PeerId
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.PeerRepository

/**
 * Maps a conversation id to a STABLE key for pin/mute + notification-suppression comparison.
 *
 * A Private conversation is addressed in the timeline by the ephemeral mesh peerID, which is not
 * stable across sessions and disagrees with the contacts/search surface (keyed by the Noise static
 * key). This use case canonicalizes a Private id to the peer's Noise key hex when it can be
 * resolved, so pin/mute survive sessions and agree across surfaces.
 *
 * Direction is the inverse of [ResolveConversationUseCase] (which resolves a stable address to a
 * live mesh peer for delivery): here we resolve a live/alias address UP to its stable identity.
 *
 * Fallback rule: when no stable key is known (e.g. an offline peer never resolved to a Noise key),
 * the original id is returned unchanged — a conversation is never dropped, and behavior matches the
 * pre-canonicalization path. Non-Private ids (mesh/channel/geohash) are already stable and returned
 * as-is.
 */
class CanonicalConversationKeyUseCase(
    private val peers: PeerRepository,
    private val contacts: ContactRepository,
) {

    suspend operator fun invoke(id: ConversationId): ConversationId = when (id) {
        is ConversationId.Private -> ConversationId.Private(canonicalPeer(id.peer))
        else -> id
    }

    private suspend fun canonicalPeer(peer: PeerId): PeerId = when (peer.kind) {
        PeerId.Kind.NOISE_STABLE -> peer
        PeerId.Kind.MESH_EPHEMERAL -> peer.toStable(peers.peer(peer)?.noiseKeyHex)
        PeerId.Kind.NOSTR_ALIAS -> peer.toStable(contacts.noiseKeyHexForNostrAlias(peer))
    }

    private fun PeerId.toStable(noiseHex: String?): PeerId =
        noiseHex?.takeIf { it.isNotBlank() }?.let { PeerId(it.lowercase()) } ?: this
}
