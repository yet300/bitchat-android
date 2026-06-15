package com.app.domain.usecase

import com.app.domain.model.ConversationId
import com.app.domain.model.Peer
import com.app.domain.model.PeerId
import com.app.domain.model.PeerIdentity
import com.app.domain.model.Reachability
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.PeerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Live [Reachability] of a conversation — the shared vocabulary the list rows and chat header
 * render.
 *
 * - `Private`: a connected peer matching the identity (mesh-ephemeral id, Noise key, or Nostr
 *   alias resolved to a Noise key) → NEARBY; else a mutual favorite (we hold their Nostr pubkey)
 *   → INTERNET; else OFFLINE. The identity-matching mirrors [ResolveConversationUseCase].
 * - `PublicMesh` / `Channel`: NEARBY while any peer is connected, else OFFLINE (local broadcast).
 * - `Geohash`: INTERNET — a Nostr room (actual relay liveness is a transport concern).
 *
 * NEARBY means "the mesh can deliver now" (direct or relayed); directness/RSSI stay a UI signal
 * read from [Peer], not part of the reachability class.
 */
class ResolveReachabilityUseCase(
    private val peers: PeerRepository,
    private val contacts: ContactRepository,
) {

    fun observe(id: ConversationId): Flow<Reachability> = when (id) {
        is ConversationId.PublicMesh,
        is ConversationId.Channel,
        ->
            peers.observeConnectionState().map { connected ->
                if (connected) Reachability.NEARBY else Reachability.OFFLINE
            }

        is ConversationId.Geohash -> flowOf(Reachability.INTERNET)

        is ConversationId.Private -> peers.observePeers().map { resolvePrivate(id.peer, it) }
    }

    private suspend fun resolvePrivate(target: PeerId, peerList: List<Peer>): Reachability {
        val noiseHex = noiseHexFor(target, peerList)

        if (peerList.any { it.isConnected && matches(it, target, noiseHex) }) {
            return Reachability.NEARBY
        }
        if (noiseHex != null && contacts.contact(PeerIdentity(noiseHex))?.isMutual == true) {
            return Reachability.INTERNET
        }
        return Reachability.OFFLINE
    }

    private suspend fun noiseHexFor(target: PeerId, peerList: List<Peer>): String? =
        when (target.kind) {
            PeerId.Kind.NOISE_STABLE -> target.raw
            PeerId.Kind.NOSTR_ALIAS -> contacts.noiseKeyHexForNostrAlias(target)
            PeerId.Kind.MESH_EPHEMERAL -> peerList.firstOrNull { it.id == target }?.noiseKeyHex
        }

    private fun matches(peer: Peer, target: PeerId, noiseHex: String?): Boolean =
        if (target.kind == PeerId.Kind.MESH_EPHEMERAL) {
            peer.id == target
        } else {
            noiseHex != null && peer.noiseKeyHex.equals(noiseHex, ignoreCase = true)
        }
}
