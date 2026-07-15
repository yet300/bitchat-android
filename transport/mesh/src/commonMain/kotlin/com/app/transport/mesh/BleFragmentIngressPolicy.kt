package com.app.transport.mesh

/**
 * Ingress policy for FRAGMENT packets (iOS BLEFragmentHandler parity for self-replay).
 *
 * Self-authored fragments (including RSR after relaunch) must be tracked for gossip
 * but never assembled — reassembly would only burn fragment-set/byte budgets.
 */
internal object BleFragmentIngressPolicy {
    fun shouldAssemble(authorPeerID: String?, localPeerID: String): Boolean =
        authorPeerID != null && authorPeerID != localPeerID
}
