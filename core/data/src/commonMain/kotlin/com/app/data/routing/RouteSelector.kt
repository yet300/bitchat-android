package com.app.data.routing

import co.touchlab.stately.collections.ConcurrentMutableMap
import com.app.common.utils.Log
import com.app.transport.routing.OutgoingEnvelope
import com.app.transport.routing.PeerKeyResolver
import com.app.transport.routing.Reachability
import com.app.transport.routing.RouteStrategy
import com.app.transport.routing.SendOutcome
import com.app.transport.routing.SessionInitiator
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Routing core (§6 Bearer SPI / Tier-2) implementing [RoutingCore].
 *
 * Replaces the repeated if/else in the old MessageRouter with a single, testable
 * "pick-best-strategy" policy:
 *   1. Sort strategies by descending [RouteStrategy.priority].
 *   2. Use the first reachable strategy whose send does not fail.
 *   3. If none can route, enqueue and (for Private) initiate a Noise handshake.
 *
 * Strategies arrive as a Metro multibound Set ([dev.zacsweers.metro.ContributesIntoSet]
 * on each implementation) — adding a transport never touches this class (OCP). The mesh
 * dependencies are narrow ports ([SessionInitiator], [PeerKeyResolver]), so the policy is
 * unit-testable with plain fakes.
 */
@SingleIn(AppScope::class)
@Inject
internal class RouteSelector(
    strategySet: Set<RouteStrategy>,
    private val outbox: Outbox,
    private val sessionInitiator: SessionInitiator,
    private val peerKeyResolver: PeerKeyResolver,
    private val scope: CoroutineScope,
) : RoutingCore {

    companion object {
        private const val TAG = "RouteSelector"
    }

    private val strategies: List<RouteStrategy> =
        strategySet.sortedByDescending { it.priority }

    init {
        // Outbox flush triggers arrive on non-suspending listener paths; hop onto the
        // app scope to run the suspending routing policy.
        outbox.onFlushNeeded = { peerID -> scope.launch { flushOutboxFor(peerID) } }
    }

    // Per-peer serialization (audit A8): the MessageRouter facade launches every call as
    // an independent coroutine on a multi-threaded scope, so without this a fresh
    // sendPrivate could overtake older envelopes being flushed when a session establishes
    // — visible as message reordering in chat. One mutex per peer key; entries are tiny
    // and peers bounded in practice.
    private val peerLocks = ConcurrentMutableMap<String, Mutex>()

    private fun lockFor(peerKey: String): Mutex = peerLocks.getOrPut(peerKey) { Mutex() }

    // -------------------------------------------------------------------------
    // RoutingCore implementation
    // -------------------------------------------------------------------------

    /**
     * Dispatches [envelope] via the best available strategy, or queues it.
     * FIFO per peer: anything already queued for the peer is drained (and either sent or
     * re-queued in order) before the new envelope is dispatched.
     */
    override suspend fun route(envelope: OutgoingEnvelope) {
        lockFor(envelope.peerID).withLock {
            outbox.drain(envelope.peerID).forEach { dispatch(it) }
            dispatch(envelope)
        }
    }

    private suspend fun dispatch(envelope: OutgoingEnvelope) {
        val eligible = strategies.filter { it.reachability(envelope.peerID) != Reachability.Unreachable }
        val dispatched = eligible.firstOrNull { strategy ->
            val outcome = strategy.send(envelope)
            Log.d(TAG, "Routing ${envelope::class.simpleName} to ${envelope.peerID.take(8)}… via ${strategy::class.simpleName} → $outcome")
            outcome !is SendOutcome.Failed
        }
        if (dispatched == null) {
            Log.d(TAG, "No route for ${envelope.peerID.take(8)}…; queuing ${envelope::class.simpleName}")
            outbox.enqueue(envelope)
            if (envelope is OutgoingEnvelope.Private) {
                Log.d(TAG, "Initiating Noise handshake for ${envelope.peerID.take(8)}…")
                sessionInitiator.initiateHandshake(envelope.peerID)
            }
        }
    }

    /** Drains and re-routes any queued envelopes for [peerID]. */
    override suspend fun flushOutboxFor(peerID: String) {
        lockFor(peerID).withLock {
            outbox.drain(peerID).forEach { dispatch(it) }
        }
        // Also flush by the peer's Noise key hex (64-char) in case it was queued under that
        // key. Locks are taken sequentially, never nested — no ordering deadlock.
        val noiseHex = try { peerKeyResolver.noiseKeyHexFor(peerID) } catch (_: Exception) { null }
        if (noiseHex != null) {
            lockFor(noiseHex).withLock {
                outbox.drain(noiseHex).forEach { dispatch(it) }
            }
        }
    }

    /** Called when the mesh peer list changes; flushes outbox entries for newly reachable peers. */
    override suspend fun onPeersUpdated(peers: List<String>) {
        peers.forEach { flushOutboxFor(it) }
    }

    /** Called when a Noise session becomes established; flushes queued messages for that peer. */
    override suspend fun onSessionEstablished(peerID: String) {
        flushOutboxFor(peerID)
    }
}
