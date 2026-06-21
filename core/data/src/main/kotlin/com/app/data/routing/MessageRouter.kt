package com.app.data.routing

import com.app.transport.model.ReadReceipt
import com.app.transport.routing.OutgoingEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Stable public facade over [RoutingCore] (implemented by [RouteSelector]).
 *
 * Exposes the send API actually used by the data layer ([sendPrivate], [sendReadReceipt],
 * [sendFavoriteNotification]). Reachability-driven outbox flushing is owned by [RouteSelector]
 * itself (via `Outbox.onFlushNeeded`), so the former lifecycle/flush passthroughs were dropped as
 * dead code rather than left as never-called orphans.
 *
 * All routing logic has moved to [RouteSelector] + [MeshRouteStrategy]/[NostrRouteStrategy].
 * The former if/else branching (duplicated 4×) is gone — the Shotgun Surgery smell is fixed.
 */
class MessageRouter internal constructor(
    private val routingCore: RoutingCore,
    private val scope: CoroutineScope,
) {

    fun sendPrivate(content: String, toPeerID: String, recipientNickname: String, messageID: String) {
        scope.launch { routingCore.route(OutgoingEnvelope.Private(toPeerID, content, recipientNickname, messageID)) }
    }

    fun sendReadReceipt(receipt: ReadReceipt, toPeerID: String) {
        scope.launch { routingCore.route(OutgoingEnvelope.Receipt(toPeerID, receipt.originalMessageID)) }
    }

    fun sendFavoriteNotification(toPeerID: String, isFavorite: Boolean) {
        scope.launch { routingCore.route(OutgoingEnvelope.Favorite(toPeerID, isFavorite)) }
    }
}
