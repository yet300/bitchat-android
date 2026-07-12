package com.app.data.gateway

import com.app.transport.model.NostrCarrierPacket
import com.app.transport.nostr.NostrEvent
import com.app.transport.nostr.NostrKind

/** Pure common gateway policy. Callers own lifecycle, scheduling, and persistence. */
class GatewayCoordinator(
    private val enabled: () -> Boolean,
    private val relaysConnected: () -> Boolean,
    private val nowSeconds: () -> Long,
    private val verifySignature: (NostrEvent) -> Boolean = NostrEvent::isValidSignature,
    private val publish: (NostrEvent, geohash: String) -> Unit,
    private val broadcast: (ByteArray) -> Unit = {},
    private val currentGeohash: () -> String? = { null },
    private val injectInbound: (NostrEvent) -> Unit = {},
    private val telemetry: (GatewayTelemetryEvent) -> Unit = {},
    private val scheduleDownlinkDrain: (delaySeconds: Long, drain: () -> Unit) -> Unit = { _, _ -> },
) {
    private val meshBroadcastIds = BoundedIds(MAX_TRACKED_IDS)
    private val publishedIds = BoundedIds(MAX_TRACKED_IDS)
    private val rebroadcastIds = BoundedIds(MAX_TRACKED_IDS)
    private val uplinkTimes = mutableMapOf<String, ArrayDeque<Long>>()
    private val queuedUplinks = ArrayDeque<QueuedUplink>()
    private val downlinkTimes = ArrayDeque<Long>()
    private val pendingDownlinks = ArrayDeque<Pair<NostrEvent, String>>()
    private var downlinkDrainScheduled = false

    fun handleMeshCarrier(payload: ByteArray, fromPeerId: String, directedToUs: Boolean) {
        val carrier = NostrCarrierPacket.decode(payload) ?: return rejected("decode")
        when (carrier.direction) {
            NostrCarrierPacket.Direction.TO_GATEWAY -> {
                if (!directedToUs || !enabled()) return
                handleUplink(carrier, fromPeerId)
            }
            NostrCarrierPacket.Direction.FROM_GATEWAY -> {
                if (directedToUs) return
                handleDownlink(carrier)
            }
            NostrCarrierPacket.Direction.TO_BRIDGE,
            NostrCarrierPacket.Direction.FROM_BRIDGE -> Unit
        }
    }

    fun flushQueuedUplinks() {
        if (!enabled() || !relaysConnected()) return
        while (queuedUplinks.isNotEmpty()) {
            val item = queuedUplinks.removeFirst()
            if (!publishedIds.contains(item.event.id)) publishAccepted(item.event, item.geohash)
        }
    }

    fun rebroadcastRelayEvent(event: NostrEvent, geohash: String) {
        if (!enabled() || !structurallyValid(event, geohash)) return
        if (meshBroadcastIds.contains(event.id) || publishedIds.contains(event.id) ||
            rebroadcastIds.contains(event.id) || pendingDownlinks.any { it.first.id == event.id }
        ) return
        if (!verifySignature(event)) return rejected("signature")
        pendingDownlinks.addLast(event to geohash)
        while (pendingDownlinks.size > MAX_PENDING_DOWNLINKS) pendingDownlinks.removeFirst()
        drainPendingDownlinks()
    }

    fun drainPendingDownlinks() {
        prune(downlinkTimes)
        while (pendingDownlinks.isNotEmpty() && downlinkTimes.size < DOWNLINKS_PER_MINUTE) {
            val (event, geohash) = pendingDownlinks.removeFirst()
            if (!fresh(event)) continue
            val packet = NostrCarrierPacket.orNull(
                NostrCarrierPacket.Direction.FROM_GATEWAY,
                geohash,
                event.toJsonString().encodeToByteArray(),
            ) ?: continue
            broadcast(packet.encode())
            rebroadcastIds.add(event.id)
            downlinkTimes.addLast(nowSeconds())
            telemetry(GatewayTelemetryEvent("downlink_sent"))
        }
        if (pendingDownlinks.isNotEmpty() && !downlinkDrainScheduled) {
            val delay = ((downlinkTimes.firstOrNull() ?: nowSeconds()) + 60 - nowSeconds()).coerceAtLeast(1)
            downlinkDrainScheduled = true
            scheduleDownlinkDrain(delay) {
                downlinkDrainScheduled = false
                drainPendingDownlinks()
            }
        }
    }

    fun clearQueues() {
        queuedUplinks.clear()
        pendingDownlinks.clear()
        uplinkTimes.clear()
        downlinkDrainScheduled = false
    }

    private fun handleUplink(carrier: NostrCarrierPacket, depositor: String) {
        val event = parseAndValidateStructure(carrier) ?: return rejected("structure")
        if (meshBroadcastIds.contains(event.id) || publishedIds.contains(event.id) ||
            queuedUplinks.any { it.event.id == event.id }
        ) return
        if (!allowUplink(depositor)) return rejected("rate_limit")
        if (!verifySignature(event)) return rejected("signature")
        val accepted = if (relaysConnected()) {
            publishAccepted(event, carrier.geohash)
            true
        } else enqueue(QueuedUplink(depositor, carrier.geohash, event))
        if (accepted && currentGeohash() == carrier.geohash) injectInbound(event)
    }

    private fun handleDownlink(carrier: NostrCarrierPacket) {
        val event = parseAndValidateStructure(carrier) ?: return rejected("structure")
        if (!verifySignature(event) || !meshBroadcastIds.add(event.id)) return
        if (currentGeohash() == carrier.geohash) injectInbound(event)
    }

    private fun parseAndValidateStructure(carrier: NostrCarrierPacket): NostrEvent? {
        if (!isValidGeohash(carrier.geohash)) return null
        val event = NostrEvent.fromJsonString(carrier.eventJson.decodeToString()) ?: return null
        return event.takeIf { structurallyValid(it, carrier.geohash) }
    }

    private fun structurallyValid(event: NostrEvent, geohash: String): Boolean =
        event.kind == NostrKind.EPHEMERAL_EVENT &&
            event.tags.any { it.size >= 2 && it[0] == "g" && it[1] == geohash } &&
            fresh(event)

    private fun fresh(event: NostrEvent): Boolean =
        kotlin.math.abs(nowSeconds() - event.createdAt.toLong()) <= MAX_EVENT_AGE_SECONDS

    private fun allowUplink(peerId: String): Boolean {
        val times = uplinkTimes.getOrPut(peerId) { ArrayDeque() }
        prune(times)
        if (times.size >= UPLINKS_PER_MINUTE) return false
        times.addLast(nowSeconds())
        return true
    }

    private fun prune(times: ArrayDeque<Long>) {
        val cutoff = nowSeconds() - 60
        while (times.firstOrNull()?.let { it < cutoff } == true) times.removeFirst()
    }

    private fun enqueue(item: QueuedUplink): Boolean {
        if (queuedUplinks.count { it.depositor == item.depositor } >= MAX_QUEUED_PER_DEPOSITOR) return false
        while (queuedUplinks.size >= MAX_QUEUED_UPLINKS) queuedUplinks.removeFirst()
        queuedUplinks.addLast(item)
        telemetry(GatewayTelemetryEvent("uplink_queued"))
        return true
    }

    private fun publishAccepted(event: NostrEvent, geohash: String) {
        publishedIds.add(event.id)
        publish(event, geohash)
        telemetry(GatewayTelemetryEvent("uplink_published"))
    }

    private fun rejected(reason: String) {
        telemetry(GatewayTelemetryEvent("rejected", reason))
    }

    private data class QueuedUplink(val depositor: String, val geohash: String, val event: NostrEvent)

    companion object {
        const val UPLINKS_PER_MINUTE = 10
        const val DOWNLINKS_PER_MINUTE = 30
        const val MAX_QUEUED_UPLINKS = 20
        const val MAX_QUEUED_PER_DEPOSITOR = 5
        const val MAX_PENDING_DOWNLINKS = 30
        const val MAX_EVENT_AGE_SECONDS = 15 * 60L
        const val MAX_TRACKED_IDS = 512

        fun isValidGeohash(value: String): Boolean = value.length in 1..NostrCarrierPacket.MAX_GEOHASH_LENGTH &&
            value.all { it in "0123456789bcdefghjkmnpqrstuvwxyz" }
    }
}

data class GatewayTelemetryEvent(val name: String, val reason: String? = null)

private class BoundedIds(private val capacity: Int) {
    private val order = ArrayDeque<String>()
    private val ids = HashSet<String>()
    fun contains(id: String): Boolean = id in ids
    fun add(id: String): Boolean {
        if (!ids.add(id)) return false
        order.addLast(id)
        if (order.size > capacity) ids.remove(order.removeFirst())
        return true
    }
}
