package com.app.transport.mesh

import com.app.transport.model.NostrCarrierPacket

/** Mesh-only uplink policy. Event creation, signing, and relay queuing stay with the Nostr layer. */
class NostrGatewaySender(
    private val relaysConnected: () -> Boolean,
    private val gatewayPeers: () -> List<String>,
    private val sendDirected: (payload: ByteArray, peerId: String) -> Boolean,
) {
    private val sentIds = HashSet<String>()
    private val sentOrder = ArrayDeque<String>()

    fun uplink(eventId: String, eventJson: ByteArray, geohash: String): Boolean {
        if (relaysConnected() || eventId in sentIds) return false
        val gateway = gatewayPeers().firstOrNull() ?: return false
        val carrier = NostrCarrierPacket.orNull(
            direction = NostrCarrierPacket.Direction.TO_GATEWAY,
            geohash = geohash,
            eventJson = eventJson,
        ) ?: return false
        if (!sendDirected(carrier.encode(), gateway)) return false
        remember(eventId)
        return true
    }

    private fun remember(eventId: String) {
        if (!sentIds.add(eventId)) return
        sentOrder.addLast(eventId)
        if (sentOrder.size > MAX_SENT_IDS) sentIds.remove(sentOrder.removeFirst())
    }

    private companion object {
        const val MAX_SENT_IDS = 512
    }
}
