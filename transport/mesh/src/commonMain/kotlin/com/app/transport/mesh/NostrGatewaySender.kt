package com.app.transport.mesh

import com.app.transport.model.NostrCarrierPacket

/** Mesh-only uplink policy. Event creation, signing, and relay queuing stay with the Nostr layer. */
class NostrGatewaySender(
    private val relaysConnected: () -> Boolean,
    private val gatewayPeers: () -> List<String>,
    private val sendDirected: (payload: ByteArray, peerId: String) -> Boolean,
    private val telemetry: (event: String, reason: String?) -> Unit = { _, _ -> },
) {
    private val sentIds = HashSet<String>()
    private val sentOrder = ArrayDeque<String>()

    fun uplink(eventId: String, eventJson: ByteArray, geohash: String): Boolean {
        if (relaysConnected()) {
            telemetry("sender_skipped", "relays_connected")
            return false
        }
        if (eventId in sentIds) {
            telemetry("sender_dropped", "duplicate")
            return false
        }
        val gateway = gatewayPeers().firstOrNull()
        if (gateway == null) {
            telemetry("sender_dropped", "no_gateway")
            return false
        }
        val carrier = NostrCarrierPacket.orNull(
            direction = NostrCarrierPacket.Direction.TO_GATEWAY,
            geohash = geohash,
            eventJson = eventJson,
        )
        if (carrier == null) {
            telemetry("sender_dropped", "invalid_carrier")
            return false
        }
        if (!sendDirected(carrier.encode(), gateway)) {
            telemetry("sender_send_failed", null)
            return false
        }
        remember(eventId)
        telemetry("sender_sent", null)
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
