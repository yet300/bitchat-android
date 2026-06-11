package com.app.data.routing

import com.app.transport.model.ReadReceipt
import com.app.transport.routing.OutgoingEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Stable public facade over [RoutingCore] (implemented by [RouteSelector]).
 *
 * Keeps the existing call-site API ([sendPrivate], [sendReadReceipt], [sendDeliveryAck],
 * [sendFavoriteNotification], [onSessionEstablished], [onPeersUpdated]) so god-class
 * callers (ChatViewModel) need no changes until Phase C dissolves them.
 *
 * All routing logic has moved to [RouteSelector] + [MeshRouteStrategy]/[NostrRouteStrategy].
 * The former if/else branching (duplicated 4×) is gone — the Shotgun Surgery smell is fixed.
 */
class MessageRouter internal constructor(
    private val routingCore: RoutingCore,
    private val scope: CoroutineScope,
) {

    // -------------------------------------------------------------------------
    // Send API — each method is now a single-line delegation to RouteSelector
    // -------------------------------------------------------------------------

    fun sendPrivate(content: String, toPeerID: String, recipientNickname: String, messageID: String) {
        scope.launch { routingCore.route(OutgoingEnvelope.Private(toPeerID, content, recipientNickname, messageID)) }
    }

    fun sendReadReceipt(receipt: ReadReceipt, toPeerID: String) {
        scope.launch { routingCore.route(OutgoingEnvelope.Receipt(toPeerID, receipt.originalMessageID)) }
    }

    fun sendDeliveryAck(messageID: String, toPeerID: String) {
        scope.launch { routingCore.route(OutgoingEnvelope.Ack(toPeerID, messageID)) }
    }

    fun sendFavoriteNotification(toPeerID: String, isFavorite: Boolean) {
        scope.launch { routingCore.route(OutgoingEnvelope.Favorite(toPeerID, isFavorite)) }
    }

    // -------------------------------------------------------------------------
    // Lifecycle / flush callbacks — delegate to RouteSelector
    // -------------------------------------------------------------------------

    fun flushOutboxFor(peerID: String) {
        scope.launch { routingCore.flushOutboxFor(peerID) }
    }


    fun onPeersUpdated(peers: List<String>) {
        scope.launch { routingCore.onPeersUpdated(peers) }
    }

    fun onSessionEstablished(peerID: String) {
        scope.launch { routingCore.onSessionEstablished(peerID) }
    }
}
