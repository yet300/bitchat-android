package com.app.data.routing

import android.util.Log
import com.app.transport.mesh.BluetoothMeshService
import com.app.transport.routing.OutgoingEnvelope
import com.app.transport.routing.Reachability
import com.app.transport.routing.RouteStrategy
import com.app.transport.routing.SendOutcome
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Routing core (§6 Bearer SPI / Tier-2) implementing [RoutingCore].
 *
 * Replaces the repeated if/else in the old MessageRouter with a single, testable
 * "pick-best-strategy" policy:
 *   1. Sort strategies by descending [RouteStrategy.priority].
 *   2. Use the first strategy that reports non-[Reachability.Unreachable].
 *   3. If none can route, enqueue and (for Private) initiate Noise handshake.
 *
 * Adding a new transport = implement [RouteStrategy] and pass it here —
 * no changes to this class (OCP).
 *
 * [MessageRouter] is kept as a stable public facade that delegates to [RoutingCore],
 * so existing call-sites (god-classes, MeshServiceHolder) do not need updates
 * until Phase C dissolves them.
 */
@SingleIn(AppScope::class)
@Inject
internal class RouteSelector(
    private val meshStrategy: MeshRouteStrategy,
    private val nostrStrategy: NostrRouteStrategy,
    private val outbox: Outbox,
    private val mesh: BluetoothMeshService,
) : RoutingCore {

    companion object {
        private const val TAG = "RouteSelector"
    }

    private val strategies: List<RouteStrategy> =
        listOf(meshStrategy, nostrStrategy).sortedByDescending { it.priority }

    init {
        outbox.onFlushNeeded = { peerID -> flushOutboxFor(peerID) }
    }

    // -------------------------------------------------------------------------
    // RoutingCore implementation
    // -------------------------------------------------------------------------

    /** Dispatches [envelope] via the best available strategy, or queues it. */
    override fun route(envelope: OutgoingEnvelope) {
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
                mesh.initiateNoiseHandshake(envelope.peerID)
            }
        }
    }

    /** Drains and re-routes any queued envelopes for [peerID]. */
    override fun flushOutboxFor(peerID: String) {
        outbox.drain(peerID).forEach { route(it) }
        // Also flush by the peer's Noise key hex (64-char) in case it was queued under that key.
        val noiseHex = try {
            mesh.getPeerInfo(peerID)?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
        } catch (_: Exception) { null }
        if (noiseHex != null) {
            outbox.drain(noiseHex).forEach { route(it) }
        }
    }

    /** Called when the mesh peer list changes; flushes outbox entries for newly reachable peers. */
    override fun onPeersUpdated(peers: List<String>) {
        peers.forEach { flushOutboxFor(it) }
    }

    /** Called when a Noise session becomes established; flushes queued messages for that peer. */
    override fun onSessionEstablished(peerID: String) {
        flushOutboxFor(peerID)
    }
}
