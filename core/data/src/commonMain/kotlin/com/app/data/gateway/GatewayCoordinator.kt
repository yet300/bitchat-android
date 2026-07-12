package com.app.data.gateway

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
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

    // Mesh ingress, relay subscriptions, and connectivity transitions are independent streams.
    // Swift confines this service to MainActor. Here each state transition is serialized, but all
    // externally supplied work is dispatched after unlocking so callback re-entry cannot deadlock.
    private val stateLock = Lock()

    fun handleMeshCarrier(payload: ByteArray, fromPeerId: String, directedToUs: Boolean) {
        dispatch(stateLock.withLock {
            val effects = mutableListOf<Effect>()
            val carrier = NostrCarrierPacket.decode(payload)
            if (carrier == null) {
                rejected("decode", effects)
                return@withLock effects
            }
            when (carrier.direction) {
                NostrCarrierPacket.Direction.TO_GATEWAY -> {
                    if (directedToUs && enabled()) handleUplink(carrier, fromPeerId, effects)
                }
                NostrCarrierPacket.Direction.FROM_GATEWAY -> {
                    if (!directedToUs) handleDownlink(carrier, effects)
                }
                NostrCarrierPacket.Direction.TO_BRIDGE,
                NostrCarrierPacket.Direction.FROM_BRIDGE -> Unit
            }
            effects
        })
    }

    fun flushQueuedUplinks() {
        dispatch(stateLock.withLock {
            val effects = mutableListOf<Effect>()
            if (!enabled() || !relaysConnected()) return@withLock effects
            while (queuedUplinks.isNotEmpty()) {
                val item = queuedUplinks.removeFirst()
                if (!publishedIds.contains(item.event.id)) publishAccepted(item.event, item.geohash, effects)
            }
            effects
        })
    }

    fun rebroadcastRelayEvent(event: NostrEvent, geohash: String) {
        dispatch(stateLock.withLock {
            val effects = mutableListOf<Effect>()
            if (!enabled() || !structurallyValid(event, geohash)) return@withLock effects
            if (meshBroadcastIds.contains(event.id) || publishedIds.contains(event.id) ||
                rebroadcastIds.contains(event.id) || pendingDownlinks.any { it.first.id == event.id }
            ) return@withLock effects
            if (!verifySignature(event)) {
                rejected("signature", effects)
                return@withLock effects
            }
            pendingDownlinks.addLast(event to geohash)
            while (pendingDownlinks.size > MAX_PENDING_DOWNLINKS) pendingDownlinks.removeFirst()
            drainPendingDownlinksLocked(effects)
            effects
        })
    }

    fun drainPendingDownlinks() {
        dispatch(stateLock.withLock {
            mutableListOf<Effect>().also(::drainPendingDownlinksLocked)
        })
    }

    fun clearQueues() = stateLock.withLock {
        queuedUplinks.clear()
        pendingDownlinks.clear()
        uplinkTimes.clear()
        downlinkDrainScheduled = false
    }

    private fun handleUplink(
        carrier: NostrCarrierPacket,
        depositor: String,
        effects: MutableList<Effect>,
    ) {
        val event = parseAndValidateStructure(carrier)
        if (event == null) {
            rejected("structure", effects)
            return
        }
        if (meshBroadcastIds.contains(event.id) || publishedIds.contains(event.id) ||
            queuedUplinks.any { it.event.id == event.id }
        ) return
        if (!allowUplink(depositor)) {
            rejected("rate_limit", effects)
            return
        }
        if (!verifySignature(event)) {
            rejected("signature", effects)
            return
        }
        val accepted = if (relaysConnected()) {
            publishAccepted(event, carrier.geohash, effects)
            true
        } else {
            enqueue(QueuedUplink(depositor, carrier.geohash, event), effects)
        }
        if (accepted && currentGeohash() == carrier.geohash) effects += Effect.Inject(event)
    }

    private fun handleDownlink(carrier: NostrCarrierPacket, effects: MutableList<Effect>) {
        val event = parseAndValidateStructure(carrier)
        if (event == null) {
            rejected("structure", effects)
            return
        }
        if (!verifySignature(event) || !meshBroadcastIds.add(event.id)) return
        if (currentGeohash() == carrier.geohash) effects += Effect.Inject(event)
    }

    private fun drainPendingDownlinksLocked(effects: MutableList<Effect>) {
        prune(downlinkTimes)
        while (pendingDownlinks.isNotEmpty() && downlinkTimes.size < DOWNLINKS_PER_MINUTE) {
            val (event, geohash) = pendingDownlinks.removeFirst()
            if (!fresh(event)) continue
            val packet = NostrCarrierPacket.orNull(
                NostrCarrierPacket.Direction.FROM_GATEWAY,
                geohash,
                event.toJsonString().encodeToByteArray(),
            ) ?: continue
            rebroadcastIds.add(event.id)
            downlinkTimes.addLast(nowSeconds())
            effects += Effect.Broadcast(packet.encode())
            effects += Effect.Telemetry(GatewayTelemetryEvent("downlink_sent"))
        }
        if (pendingDownlinks.isNotEmpty() && !downlinkDrainScheduled) {
            val delay = ((downlinkTimes.firstOrNull() ?: nowSeconds()) + 60 - nowSeconds()).coerceAtLeast(1)
            downlinkDrainScheduled = true
            effects += Effect.ScheduleDrain(delay)
        }
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

    private fun enqueue(item: QueuedUplink, effects: MutableList<Effect>): Boolean {
        if (queuedUplinks.count { it.depositor == item.depositor } >= MAX_QUEUED_PER_DEPOSITOR) return false
        while (queuedUplinks.size >= MAX_QUEUED_UPLINKS) queuedUplinks.removeFirst()
        queuedUplinks.addLast(item)
        effects += Effect.Telemetry(GatewayTelemetryEvent("uplink_queued"))
        return true
    }

    private fun publishAccepted(event: NostrEvent, geohash: String, effects: MutableList<Effect>) {
        publishedIds.add(event.id)
        effects += Effect.Publish(event, geohash)
        effects += Effect.Telemetry(GatewayTelemetryEvent("uplink_published"))
    }

    private fun rejected(reason: String, effects: MutableList<Effect>) {
        effects += Effect.Telemetry(GatewayTelemetryEvent("rejected", reason))
    }

    private fun dispatch(effects: List<Effect>) {
        effects.forEach { effect ->
            when (effect) {
                is Effect.Publish -> publish(effect.event, effect.geohash)
                is Effect.Broadcast -> broadcast(effect.payload)
                is Effect.Inject -> injectInbound(effect.event)
                is Effect.Telemetry -> telemetry(effect.event)
                is Effect.ScheduleDrain -> scheduleDownlinkDrain(effect.delaySeconds) {
                    val delayedEffects = stateLock.withLock {
                        downlinkDrainScheduled = false
                        mutableListOf<Effect>().also(::drainPendingDownlinksLocked)
                    }
                    dispatch(delayedEffects)
                }
            }
        }
    }

    private data class QueuedUplink(val depositor: String, val geohash: String, val event: NostrEvent)

    private sealed interface Effect {
        data class Publish(val event: NostrEvent, val geohash: String) : Effect
        data class Broadcast(val payload: ByteArray) : Effect
        data class Inject(val event: NostrEvent) : Effect
        data class Telemetry(val event: GatewayTelemetryEvent) : Effect
        data class ScheduleDrain(val delaySeconds: Long) : Effect
    }

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
