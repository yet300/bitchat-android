package com.app.transport.mesh

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import com.app.transport.model.RoutedPacket
import kotlinx.coroutines.channels.Channel

/**
 * Bounded, priority-ordered replacement for the `Channel(UNLIMITED)` that fed [BleSendCore]'s single
 * broadcast consumer. Frames are dequeued highest-priority-first (own before relay; interactive
 * before bulk media — see [BleOutboundPriority]) and, within a priority, FIFO by arrival. When the
 * queue is full the **lowest-priority, newest** frame is dropped (relay/media tail shed first, our
 * own interactive traffic protected) and [onDrop] fires for telemetry.
 *
 * A conflated [signal] channel wakes the consumer; the actual items live under [lock] so priority
 * ordering and capped dropping are exact. This keeps the previous single-consumer / FIFO-per-priority
 * discipline the send-path goldens rely on (they enqueue one frame at a time, so ordering is
 * unchanged) while removing the unbounded-growth failure mode under a message storm.
 */
internal class BleOutboundFrameQueue(
    private val capacity: Int,
    private val onDrop: () -> Unit,
) {
    private class Entry(val routed: RoutedPacket, val priority: Int, val seq: Long)

    private val lock = Lock()
    private val items = ArrayList<Entry>()
    private var seq = 0L
    private val signal = Channel<Unit>(Channel.CONFLATED)

    fun offer(routed: RoutedPacket, priority: BleOutboundPriority) {
        lock.withLock {
            insertOrdered(Entry(routed, priority.ordinal, seq++))
            while (items.size > capacity) {
                items.removeAt(items.size - 1) // lowest priority, newest arrival
                onDrop()
            }
        }
        signal.trySend(Unit)
    }

    /** Suspends until a frame is available, then returns the highest-priority one. */
    suspend fun receive(): RoutedPacket {
        while (true) {
            lock.withLock { if (items.isNotEmpty()) return items.removeAt(0).routed }
            signal.receive() // may be spurious after a race; loop re-checks under the lock
        }
    }

    fun close() = signal.close()

    // Ascending by (priority, seq): index 0 is the next to send, last is the first to drop.
    private fun insertOrdered(entry: Entry) {
        var lo = 0
        var hi = items.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val e = items[mid]
            val cmp = if (e.priority != entry.priority) e.priority - entry.priority
            else (e.seq - entry.seq).let { if (it < 0) -1 else if (it > 0) 1 else 0 }
            if (cmp <= 0) lo = mid + 1 else hi = mid
        }
        items.add(lo, entry)
    }
}
