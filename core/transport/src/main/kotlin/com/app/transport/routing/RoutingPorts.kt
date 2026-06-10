package com.app.transport.routing

import com.app.transport.nostr.NostrIdentity

/**
 * Narrow ports (ISP) consumed by the routing policy so it does not depend on the
 * concrete BluetoothMeshService / static NostrIdentityBridge. Implementations are
 * bound in :core:data (mesh-backed) and :app (identity bridge).
 */

/** Kicks off a Noise handshake with a peer (mesh-backed). */
fun interface SessionInitiator {
    fun initiateHandshake(peerID: String)
}

/** Resolves a peer's stable Noise public key (64-char hex), if known. */
fun interface PeerKeyResolver {
    fun noiseKeyHexFor(peerID: String): String?
}

/** Supplies the current Nostr identity (npub + signing keys), if one exists. */
fun interface NostrIdentityProvider {
    fun current(): NostrIdentity?
}
