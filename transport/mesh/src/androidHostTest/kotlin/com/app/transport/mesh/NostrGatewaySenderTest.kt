package com.app.transport.mesh

import com.app.transport.model.NostrCarrierPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NostrGatewaySenderTest {
    private val eventJson = "{\"id\":\"event-1\"}".encodeToByteArray()

    @Test
    fun `connected relays keep the event on the direct Nostr path`() {
        val sent = mutableListOf<String>()
        val sender = NostrGatewaySender(
            relaysConnected = { true },
            gatewayPeers = { listOf("gateway-a") },
            sendDirected = { _, peer -> sent += peer; true },
        )

        assertFalse(sender.uplink("event-1", eventJson, "u4pruy"))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `offline sender chooses exactly one advertised gateway`() {
        val sent = mutableListOf<Pair<String, ByteArray>>()
        val sender = NostrGatewaySender(
            relaysConnected = { false },
            gatewayPeers = { listOf("gateway-a", "gateway-b") },
            sendDirected = { payload, peer -> sent += peer to payload; true },
        )

        assertTrue(sender.uplink("event-1", eventJson, "u4pruy"))
        assertEquals(listOf("gateway-a"), sent.map { it.first })
        assertEquals(
            NostrCarrierPacket.Direction.TO_GATEWAY,
            NostrCarrierPacket.decode(sent.single().second)?.direction,
        )
    }

    @Test
    fun `no gateway or rejected mesh send reports failure and remains retryable`() {
        var accepts = false
        val sender = NostrGatewaySender(
            relaysConnected = { false },
            gatewayPeers = { if (accepts) listOf("gateway-a") else emptyList() },
            sendDirected = { _, _ -> accepts },
        )

        assertFalse(sender.uplink("event-1", eventJson, "u4pruy"))
        accepts = true
        assertTrue(sender.uplink("event-1", eventJson, "u4pruy"))
        assertFalse(sender.uplink("event-1", eventJson, "u4pruy"))
    }

    @Test
    fun `sender emits observable outcomes for no gateway send failure and duplicate`() {
        val events = mutableListOf<Pair<String, String?>>()
        var accepting = false
        val sender = NostrGatewaySender(
            relaysConnected = { false },
            gatewayPeers = { if (accepting) listOf("gateway-a") else emptyList() },
            sendDirected = { _, _ -> accepting },
            telemetry = { event, reason -> events += event to reason },
        )

        assertFalse(sender.uplink("event-1", eventJson, "u4pruy"))
        accepting = true
        assertTrue(sender.uplink("event-1", eventJson, "u4pruy"))
        assertFalse(sender.uplink("event-1", eventJson, "u4pruy"))

        assertEquals(
            listOf(
                "sender_dropped" to "no_gateway",
                "sender_sent" to null,
                "sender_dropped" to "duplicate",
            ),
            events,
        )
    }
}
