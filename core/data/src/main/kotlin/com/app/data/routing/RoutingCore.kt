package com.app.data.routing

import com.app.transport.routing.OutgoingEnvelope

/**
 * Public routing facade consumed by [MessageRouter] (the stable call-site API).
 *
 */
interface RoutingCore {
    /** Dispatches [envelope] via the best available strategy, or queues it. */
    fun route(envelope: OutgoingEnvelope)

    /** Drains and re-routes any queued envelopes for [peerID]. */
    fun flushOutboxFor(peerID: String)

    /** Called when the mesh peer list changes; flushes outbox for newly reachable peers. */
    fun onPeersUpdated(peers: List<String>)

    /** Called when a Noise session becomes established; flushes queued messages for that peer. */
    fun onSessionEstablished(peerID: String)
}
