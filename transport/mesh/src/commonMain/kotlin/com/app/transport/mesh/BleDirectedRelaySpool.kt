package com.app.transport.mesh

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import com.app.transport.protocol.BitchatPacket

/**
 * Short-lived store for directed packets when this node currently has zero writable BLE links.
 *
 * Port of iOS `Services/BLE/BLEDirectedRelaySpool.swift`. Distinct from [StoreForwardManager]
 * (hours-long offline peer mailbag): this bag lives for seconds and only covers the radio-up race
 * where a DM/handshake leaves the app before GATT is writable.
 */
class BleDirectedRelaySpool {
    data class Entry(
        val recipient: String,
        val packet: BitchatPacket,
    )

    private data class Stored(
        val packet: BitchatPacket,
        val enqueuedAtMs: Long,
    )

    private val lock = Lock()
    private val packetsByRecipient = linkedMapOf<String, LinkedHashMap<String, Stored>>()

    val isEmpty: Boolean
        get() = lock.withLock { packetsByRecipient.isEmpty() }

    val count: Int
        get() = lock.withLock {
            packetsByRecipient.values.sumOf { it.size }
        }

    /**
     * @return true if newly stored, false if [messageID] was already queued for [recipient].
     */
    fun enqueue(
        packet: BitchatPacket,
        recipient: String,
        messageID: String,
        enqueuedAtMs: Long,
    ): Boolean = lock.withLock {
        val packets = packetsByRecipient.getOrPut(recipient) { linkedMapOf() }
        if (packets.containsKey(messageID)) return false
        packets[messageID] = Stored(packet, enqueuedAtMs)
        true
    }

    /**
     * Removes every entry and returns those still within [windowMs] of [nowMs].
     * Callers re-broadcast; if links are still missing, the send path may re-spool.
     */
    fun drainUnexpired(nowMs: Long, windowMs: Long): List<Entry> = lock.withLock {
        val entries = mutableListOf<Entry>()
        for ((recipient, packets) in packetsByRecipient) {
            for (stored in packets.values) {
                if (nowMs - stored.enqueuedAtMs <= windowMs) {
                    entries.add(Entry(recipient, stored.packet))
                }
            }
        }
        packetsByRecipient.clear()
        entries
    }

    fun pruneExpired(nowMs: Long, windowMs: Long) {
        lock.withLock {
            if (packetsByRecipient.isEmpty()) return
            val pruned = linkedMapOf<String, LinkedHashMap<String, Stored>>()
            for ((recipient, packets) in packetsByRecipient) {
                val fresh = linkedMapOf<String, Stored>()
                for ((id, stored) in packets) {
                    if (nowMs - stored.enqueuedAtMs <= windowMs) {
                        fresh[id] = stored
                    }
                }
                if (fresh.isNotEmpty()) pruned[recipient] = fresh
            }
            packetsByRecipient.clear()
            packetsByRecipient.putAll(pruned)
        }
    }

    fun clear() = lock.withLock { packetsByRecipient.clear() }
}
