package com.bitchat.android.core.domain.usecase

import com.bitchat.android.core.domain.model.PeerId
import com.bitchat.android.core.domain.repository.ContactRepository
import com.bitchat.android.core.domain.repository.PeerRepository

/**
 * Resolves the canonical peer address (a port of the ConversationAliasResolver logic).
 *
 * Rule: for a stable Noise key or a Nostr alias, if that same person is currently online over
 * mesh, prefer the live mesh peerID (cheaper and faster to deliver). Otherwise keep the original
 * address.
 */
class ResolveConversationUseCase(
    private val peers: PeerRepository,
    private val contacts: ContactRepository,
) {

    suspend operator fun invoke(target: PeerId): PeerId = when (target.kind) {
        PeerId.Kind.MESH_EPHEMERAL -> target
        PeerId.Kind.NOISE_STABLE -> resolveByNoiseHex(target.raw) ?: target
        PeerId.Kind.NOSTR_ALIAS -> {
            val noiseHex = contacts.noiseKeyHexForNostrAlias(target) ?: return target
            resolveByNoiseHex(noiseHex) ?: target
        }
    }

    private suspend fun resolveByNoiseHex(noiseHex: String): PeerId? =
        peers.snapshot()
            .firstOrNull { it.isConnected && it.noiseKeyHex.equals(noiseHex, ignoreCase = true) }
            ?.id
}
