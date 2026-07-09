package com.app.transport.mesh

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock

/**
 * Per-link outbound buffer: the commonMain home of everything the four radio write paths used to
 * either drop on the floor or serialize with a blocking `synchronized`. One instance per BLE link
 * address, owned by [BleOutboundDispatcher].
 *
 * It reconciles three concerns the reference iOS client handles across
 * [BLEOutboundNotificationBuffer]/[BLEOutboundWriteBuffer] and their `*IsReady` drains:
 *
 *  1. **Chunking** — a frame is split to the link's usable MTU here (the platform only reports
 *     `maxChunkBytes`); receivers reassemble via `BleFrameAssembler`.
 *  2. **Ordering** — a frame's chunk run must hit the wire contiguously. The [lock] plus the FIFO
 *     [frames] deque replace the old per-address `emissionLocks`: once a link has any pending chunk,
 *     every later frame is appended and drained in order, so two frames' chunks never interleave.
 *  3. **Busy handling** — mirror the iOS `writeOrEnqueue`/`updateValue` model: on [submit] try to
 *     write each chunk **synchronously**; on the first [ChunkWriteResult.BUSY], stash the remaining
 *     chunks and return. [drain] (called on the platform readiness signal or the dispatcher's backup
 *     tick) resumes. Nothing is lost to transient back-pressure.
 *
 * The synchronous fast path (idle link, stack not busy) writes under the lock with no coroutine and
 * no suspension — preserving the observable "chunks are emitted before the send call returns"
 * behavior the golden tests pin.
 */
internal class BleLinkOutboundBuffer(
    private val onDrop: () -> Unit,
) {
    private class PendingFrame(
        val chunks: ArrayDeque<ByteArray>,
        val priority: BleOutboundPriority,
        var started: Boolean,
        val bytes: Int,
    )

    private val lock = Lock()
    private val frames = ArrayDeque<PendingFrame>()
    private var pendingBytes = 0

    // Latest platform binding for this link; drain() (backup tick / readiness) reuses it.
    private var writer: BleChunkWriter? = null

    val isEmpty: Boolean get() = lock.withLock { frames.isEmpty() }

    /**
     * Chunk [frame] to [maxChunkBytes], writing synchronously while the link is idle and the stack
     * accepts chunks; queue the remainder on the first BUSY. Returns false only when the link is
     * [ChunkWriteResult.GONE] with nothing salvageable — the caller then drops the link.
     */
    fun submit(
        frame: ByteArray,
        maxChunkBytes: Int,
        writer: BleChunkWriter,
        priority: BleOutboundPriority,
        capBytes: Int,
    ): Boolean = lock.withLock {
        this.writer = writer
        val chunks = chunk(frame, maxChunkBytes)

        // Fast path: link idle → try to flush this frame's chunks right now, contiguously.
        if (frames.isEmpty()) {
            var i = 0
            while (i < chunks.size) {
                when (writer.writeChunk(chunks[i])) {
                    ChunkWriteResult.SENT -> i++
                    // link died mid/at-start; head (if any) is gone, nothing to queue.
                    ChunkWriteResult.GONE -> return@withLock true
                    ChunkWriteResult.BUSY -> {
                        // Stash the tail (chunks i..end); this frame's head already went out, so it
                        // is now the in-flight "started" frame — it must never be dropped.
                        val tail = ArrayDeque<ByteArray>()
                        var b = 0
                        for (j in i until chunks.size) { tail.addLast(chunks[j]); b += chunks[j].size }
                        frames.addLast(PendingFrame(tail, priority, started = i > 0, bytes = b))
                        pendingBytes += b
                        return@withLock true
                    }
                }
            }
            return@withLock true // all chunks sent synchronously
        }

        // Link already draining: append in order so runs stay contiguous, then trim to cap.
        val bytes = chunks.sumOf { it.size }
        frames.addLast(PendingFrame(ArrayDeque(chunks), priority, started = false, bytes = bytes))
        pendingBytes += bytes
        trimToCap(capBytes)
        true
    }

    /**
     * Resume draining after a readiness signal or on the backup tick. Returns true while chunks
     * remain queued (the dispatcher keeps ticking), false once empty. GONE clears the whole buffer.
     */
    fun drain(): Boolean = lock.withLock {
        val w = writer ?: return@withLock frames.isNotEmpty()
        while (frames.isNotEmpty()) {
            val front = frames.first()
            while (front.chunks.isNotEmpty()) {
                when (w.writeChunk(front.chunks.first())) {
                    ChunkWriteResult.SENT -> {
                        val sent = front.chunks.removeFirst()
                        front.started = true
                        pendingBytes -= sent.size
                    }
                    ChunkWriteResult.BUSY -> return@withLock true // still pending; retry on next signal/tick
                    ChunkWriteResult.GONE -> { clearLocked(); return@withLock false }
                }
            }
            frames.removeFirst()
        }
        false
    }

    fun clear() = lock.withLock { clearLocked() }

    private fun clearLocked() {
        frames.clear()
        pendingBytes = 0
        writer = null
    }

    /** Drop the lowest-priority (highest ordinal), newest, not-yet-started frames until under cap. */
    private fun trimToCap(capBytes: Int) {
        while (pendingBytes > capBytes) {
            // Never the in-flight started frame (front, if started); scan the rest for the victim.
            var victim = -1
            var worst = Int.MIN_VALUE
            for (idx in frames.indices) {
                val f = frames[idx]
                if (f.started) continue
                if (f.priority.ordinal >= worst) { worst = f.priority.ordinal; victim = idx }
            }
            if (victim < 0) break // only started frames remain — cannot drop without corrupting the stream
            val removed = frames.removeAt(victim)
            pendingBytes -= removed.bytes
            onDrop()
        }
    }

    private fun chunk(frame: ByteArray, maxChunkBytes: Int): List<ByteArray> {
        val max = maxChunkBytes.coerceAtLeast(1)
        if (frame.size <= max) return listOf(frame)
        val out = ArrayList<ByteArray>((frame.size + max - 1) / max)
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + max, frame.size)
            out.add(frame.copyOfRange(offset, end))
            offset = end
        }
        return out
    }
}
