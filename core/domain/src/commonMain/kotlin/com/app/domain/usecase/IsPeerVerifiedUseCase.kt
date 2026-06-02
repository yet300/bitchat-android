package com.app.domain.usecase

import com.app.domain.model.PeerId
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.PeerRepository

/**
 * Whether a peer's fingerprint is verified. Business rule: Nostr/geohash aliases cannot be
 * verified (no Noise fingerprint), so they are always unverified.
 */
class IsPeerVerifiedUseCase(
    private val peers: PeerRepository,
    private val identity: IdentityRepository,
) {
    suspend operator fun invoke(peer: PeerId): Boolean {
        if (peer.kind == PeerId.Kind.NOSTR_ALIAS) return false
        val fingerprint = peers.peer(peer)?.fingerprint ?: return false
        return identity.isVerified(fingerprint)
    }
}
