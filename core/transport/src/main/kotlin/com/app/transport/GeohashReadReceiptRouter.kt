package com.app.transport

/**
 * Routes a read receipt over the internet relay when the recipient is a geohash alias rather than a
 * reachable mesh peer.
 *
 * The implementation (geohash alias registry + message router) lives in the app module, so the mesh
 * layer stays unaware of Nostr/relay routing.
 */
fun interface GeohashReadReceiptRouter {
    /** @return true if [toPeerId] is a geohash alias and the receipt was routed via the relay. */
    fun routeIfGeohashAlias(messageId: String, toPeerId: String): Boolean
}
