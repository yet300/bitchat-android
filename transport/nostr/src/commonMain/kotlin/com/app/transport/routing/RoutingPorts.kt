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

/**
 * Last-resort store-and-forward: seal a private message and hand it to connected trusted couriers to
 * carry to a currently-unreachable recipient (BitchatPacket 0x04). Idempotent per message id, so a
 * repeated flush does not re-deposit. Backed by the courier coordinator in :core:data.
 */
fun interface CourierDepositor {
    suspend fun attemptDeposit(messageID: String, content: String, recipientPeerID: String): Boolean
}

/** Supplies the current Nostr identity (npub + signing keys), if one exists. */
fun interface NostrIdentityProvider {
    fun current(): NostrIdentity?
}

/**
 * Live source of our own mesh peer id (first 16 hex of the Noise identity fingerprint).
 * Backed by EncryptionService, so it survives a panic reset without re-wiring — replaces
 * the mutable NostrMessageSender.senderPeerID that the UI had to keep assigning.
 */
fun interface MeshPeerIdSource {
    fun current(): String
}
