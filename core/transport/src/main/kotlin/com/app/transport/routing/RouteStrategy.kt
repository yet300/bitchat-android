package com.app.transport.routing

/**
 * Reachability of a peer via a particular transport strategy.
 */
enum class Reachability {
    /** Peer is directly connected (e.g., active BLE Noise session). */
    Direct,
    /** Peer is reachable via an intermediate relay (e.g., Nostr). */
    ViaRelay,
    /** Peer is not reachable via this strategy at the moment. */
    Unreachable,
}

/**
 * Outcome of a send attempt.
 */
sealed class SendOutcome {
    /** Message was successfully dispatched. */
    object Sent : SendOutcome()
    /** Message was queued for later delivery. */
    object Queued : SendOutcome()
    /** Send failed with a reason. */
    data class Failed(val reason: String) : SendOutcome()
}

/**
 * SPI for pluggable routing strategies (§6 Bearer SPI / OCP).
 *
 * Implementations live in :core:data (they know both transport and data layers).
 * The interface itself lives in :core:transport so the transport vocabulary
 * (OutgoingEnvelope, String peerID) does not bleed into the domain.
 *
 * Adding a new transport = implement RouteStrategy + register in RouteSelector
 * without touching the routing core.
 */
interface RouteStrategy {
    /** Higher priority wins when multiple strategies can reach the same peer. */
    val priority: Int

    /**
     * Checks current reachability without side-effects.
     * Called by [com.app.data.routing.RouteSelector] before dispatching.
     */
    fun reachability(peerID: String): Reachability

    /**
     * Dispatches [envelope] to the peer. Called only when
     * [reachability] returned something other than [Reachability.Unreachable].
     */
    fun send(envelope: OutgoingEnvelope): SendOutcome
}
