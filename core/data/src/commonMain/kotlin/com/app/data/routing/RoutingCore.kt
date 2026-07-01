package com.app.data.routing

import com.app.transport.routing.OutgoingEnvelope

/**
 * Public routing facade consumed by [MessageRouter] (the stable call-site API).
 *
 * All operations are suspending: the policy awaits each strategy's send outcome
 * (honest [com.app.transport.routing.SendOutcome] contract).
 */
internal interface RoutingCore {
    /** Dispatches [envelope] via the best available strategy, or queues it. */
    suspend fun route(envelope: OutgoingEnvelope)

    /** Drains and re-routes any queued envelopes for [peerID]. */
    suspend fun flushOutboxFor(peerID: String)

    /** Called when the mesh peer list changes; flushes outbox for newly reachable peers. */
    suspend fun onPeersUpdated(peers: List<String>)

    /** Called when a Noise session becomes established; flushes queued messages for that peer. */
    suspend fun onSessionEstablished(peerID: String)
}
