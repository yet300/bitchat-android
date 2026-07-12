package com.app.data.gateway

import com.app.transport.mesh.NostrGatewaySender
import com.app.transport.nostr.NostrEvent
import com.app.transport.nostr.NostrKind
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatewayCoordinatorTest {
    private var now = 1_700_000_000L

    private fun event(id: String = "id-1", geohash: String = "u4pruy", kind: Int = NostrKind.EPHEMERAL_EVENT) =
        NostrEvent(
            id = id,
            pubkey = "11".repeat(32),
            createdAt = now.toInt(),
            kind = kind,
            tags = listOf(listOf("g", geohash)),
            content = "hello",
            sig = "22".repeat(64),
        )

    @Test
    fun `mesh sender to enabled gateway publishes to fake relay`() {
        val published = mutableListOf<NostrEvent>()
        val coordinator = GatewayCoordinator(
            enabled = { true }, relaysConnected = { true }, nowSeconds = { now },
            verifySignature = { true }, publish = { event, _ -> published += event },
        )
        val sender = NostrGatewaySender(
            relaysConnected = { false }, gatewayPeers = { listOf("gateway") },
            sendDirected = { payload, _ -> coordinator.handleMeshCarrier(payload, "sender", directedToUs = true); true },
        )
        val event = event()

        assertTrue(sender.uplink(event.id, event.toJsonString().encodeToByteArray(), "u4pruy"))
        assertEquals(listOf(event.id), published.map { it.id })
    }

    @Test
    fun `disabled gateway and non-directed uplink refuse deposits`() {
        var enabled = false
        var publishes = 0
        val coordinator = GatewayCoordinator(
            enabled = { enabled }, relaysConnected = { true }, nowSeconds = { now },
            verifySignature = { true }, publish = { _, _ -> publishes++ },
        )
        val carrier = carrier(event())

        coordinator.handleMeshCarrier(carrier, "sender", directedToUs = true)
        enabled = true
        coordinator.handleMeshCarrier(carrier, "sender", directedToUs = false)
        assertEquals(0, publishes)
    }

    @Test
    fun `cheap structural gates run before signature verification`() {
        var verifies = 0
        val coordinator = GatewayCoordinator(
            enabled = { true }, relaysConnected = { true }, nowSeconds = { now },
            verifySignature = { verifies++; true }, publish = { _, _ -> error("must not publish") },
        )

        coordinator.handleMeshCarrier(carrier(event(kind = 1)), "sender", directedToUs = true)
        coordinator.handleMeshCarrier(carrier(event(geohash = "u4pruy"), carrierGeohash = "9q8yy"), "sender", true)
        assertEquals(0, verifies)
    }

    @Test
    fun `uplink rate limit is per depositor and slides after a minute`() {
        var publishes = 0
        val coordinator = GatewayCoordinator(
            enabled = { true }, relaysConnected = { true }, nowSeconds = { now },
            verifySignature = { true }, publish = { _, _ -> publishes++ },
        )
        repeat(GatewayCoordinator.UPLINKS_PER_MINUTE) { index ->
            coordinator.handleMeshCarrier(carrier(event("id-$index")), "sender-a", true)
        }
        coordinator.handleMeshCarrier(carrier(event("over")), "sender-a", true)
        coordinator.handleMeshCarrier(carrier(event("other")), "sender-b", true)
        assertEquals(GatewayCoordinator.UPLINKS_PER_MINUTE + 1, publishes)
        now += 61
        coordinator.handleMeshCarrier(carrier(event("later")), "sender-a", true)
        assertEquals(GatewayCoordinator.UPLINKS_PER_MINUTE + 2, publishes)
    }

    @Test
    fun `bridge directions are decoded but ignored by gateway policy`() {
        var acted = false
        val coordinator = GatewayCoordinator(
            enabled = { true }, relaysConnected = { true }, nowSeconds = { now },
            verifySignature = { acted = true; true }, publish = { _, _ -> acted = true },
        )
        listOf(
            com.app.transport.model.NostrCarrierPacket.Direction.TO_BRIDGE,
            com.app.transport.model.NostrCarrierPacket.Direction.FROM_BRIDGE,
        ).forEach { direction ->
            val packet = com.app.transport.model.NostrCarrierPacket(direction, "u4pruy", event().toJsonString().encodeToByteArray())
            coordinator.handleMeshCarrier(packet.encode(), "sender", direction == com.app.transport.model.NostrCarrierPacket.Direction.TO_BRIDGE)
        }
        assertFalse(acted)
    }

    @Test
    fun `downlink overflow schedules one drain when the airtime window opens`() {
        val broadcasts = mutableListOf<ByteArray>()
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val coordinator = GatewayCoordinator(
            enabled = { true }, relaysConnected = { true }, nowSeconds = { now },
            verifySignature = { true }, publish = { _, _ -> }, broadcast = { broadcasts += it },
            scheduleDownlinkDrain = { delay, work -> scheduled += delay to work },
        )
        repeat(GatewayCoordinator.DOWNLINKS_PER_MINUTE + 1) { index ->
            coordinator.rebroadcastRelayEvent(event("relay-$index"), "u4pruy")
        }

        assertEquals(GatewayCoordinator.DOWNLINKS_PER_MINUTE, broadcasts.size)
        assertEquals(1, scheduled.size)
        now += 61
        scheduled.single().second()
        assertEquals(GatewayCoordinator.DOWNLINKS_PER_MINUTE + 1, broadcasts.size)
    }

    @Test
    fun `inline scheduler runs drain after coordinator unlocks`() {
        val broadcasts = mutableListOf<ByteArray>()
        val coordinator = GatewayCoordinator(
            enabled = { true }, relaysConnected = { true }, nowSeconds = { now },
            verifySignature = { true }, publish = { _, _ -> }, broadcast = { broadcasts += it },
            scheduleDownlinkDrain = { _, work ->
                now += 61
                work()
            },
        )

        repeat(GatewayCoordinator.DOWNLINKS_PER_MINUTE + 1) { index ->
            coordinator.rebroadcastRelayEvent(event("inline-$index"), "u4pruy")
        }

        assertEquals(GatewayCoordinator.DOWNLINKS_PER_MINUTE + 1, broadcasts.size)
    }

    @Test
    fun `quiet relay recovery flushes queued uplinks without an inbound relay event`() {
        var connected = false
        val published = mutableListOf<NostrEvent>()
        val coordinator = GatewayCoordinator(
            enabled = { true }, relaysConnected = { connected }, nowSeconds = { now },
            verifySignature = { true }, publish = { event, _ -> published += event },
        )

        coordinator.handleMeshCarrier(carrier(event("queued")), "sender", directedToUs = true)
        assertTrue(published.isEmpty())
        connected = true
        coordinator.flushQueuedUplinks()

        assertEquals(listOf("queued"), published.map { it.id })
    }

    @Test
    fun `concurrent reconnect and mesh deposit preserve queue dedup`() = runBlocking {
        val connected = AtomicBoolean(false)
        val published = Collections.synchronizedList(mutableListOf<NostrEvent>())
        val coordinator = GatewayCoordinator(
            enabled = { true }, relaysConnected = connected::get, nowSeconds = { now },
            verifySignature = { true }, publish = { event, _ -> published += event },
        )
        val payload = carrier(event("concurrent"))
        coordinator.handleMeshCarrier(payload, "sender", directedToUs = true)

        (0 until 64).map { index ->
            async(Dispatchers.Default) {
                if (index % 2 == 0) {
                    connected.set(true)
                    coordinator.flushQueuedUplinks()
                } else {
                    coordinator.handleMeshCarrier(payload, "sender", directedToUs = true)
                }
            }
        }.awaitAll()

        assertEquals(listOf("concurrent"), published.map { it.id })
    }

    private fun carrier(event: NostrEvent, carrierGeohash: String = "u4pruy"): ByteArray =
        com.app.transport.model.NostrCarrierPacket(
            com.app.transport.model.NostrCarrierPacket.Direction.TO_GATEWAY,
            carrierGeohash,
            event.toJsonString().encodeToByteArray(),
        ).encode()
}
