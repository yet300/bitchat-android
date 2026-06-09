package com.app.data.routing

import com.app.transport.mesh.BluetoothMeshService
import com.app.transport.model.ReadReceipt
import com.app.transport.routing.OutgoingEnvelope

/**
 * Stable public facade over [RoutingCore] (implemented by [RouteSelector]).
 *
 * Keeps the existing call-site API ([sendPrivate], [sendReadReceipt], [sendDeliveryAck],
 * [sendFavoriteNotification], [onSessionEstablished], [onPeersUpdated]) so god-class
 * callers (ChatViewModel, MeshServiceHolder) need no changes until Phase C dissolves them.
 *
 * All routing logic has moved to [RouteSelector] + [MeshRouteStrategy]/[NostrRouteStrategy].
 * The former if/else branching (duplicated 4×) is gone — the Shotgun Surgery smell is fixed.
 */
class MessageRouter private constructor(
    private val routingCore: RoutingCore,
    private val mesh: BluetoothMeshService,
) {
    companion object {
        private const val TAG = "MessageRouter"
        @Volatile private var INSTANCE: MessageRouter? = null

        /** Returns the graph-owned singleton if it has been created, or null. */
        fun tryGetInstance(): MessageRouter? = INSTANCE

        /**
         * Graph entry-point called by [com.bitchat.android.di.AndroidDataBindings].
         * Stores the graph-owned instance so [tryGetInstance] works for legacy call-sites.
         */
        fun getInstance(
            routingCore: RoutingCore,
            mesh: BluetoothMeshService,
        ): MessageRouter {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MessageRouter(routingCore, mesh).also { INSTANCE = it }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Send API — each method is now a single-line delegation to RouteSelector
    // -------------------------------------------------------------------------

    fun sendPrivate(content: String, toPeerID: String, recipientNickname: String, messageID: String) {
        routingCore.route(OutgoingEnvelope.Private(toPeerID, content, recipientNickname, messageID))
    }

    fun sendReadReceipt(receipt: ReadReceipt, toPeerID: String) {
        routingCore.route(OutgoingEnvelope.Receipt(toPeerID, receipt.originalMessageID))
    }

    fun sendDeliveryAck(messageID: String, toPeerID: String) {
        routingCore.route(OutgoingEnvelope.Ack(toPeerID, messageID))
    }

    fun sendFavoriteNotification(toPeerID: String, isFavorite: Boolean) {
        routingCore.route(OutgoingEnvelope.Favorite(toPeerID, isFavorite))
    }

    // -------------------------------------------------------------------------
    // Lifecycle / flush callbacks — delegate to RouteSelector
    // -------------------------------------------------------------------------

    fun flushOutboxFor(peerID: String) = routingCore.flushOutboxFor(peerID)

    fun flushAllOutbox() {
        // RouteSelector exposes per-peer flush; a global flush iterates all known peers.
        val peers = try { mesh.getPeerNicknames().keys.toList() } catch (_: Exception) { emptyList() }
        peers.forEach { routingCore.flushOutboxFor(it) }
    }

    fun onPeersUpdated(peers: List<String>) = routingCore.onPeersUpdated(peers)

    fun onSessionEstablished(peerID: String) = routingCore.onSessionEstablished(peerID)
}
