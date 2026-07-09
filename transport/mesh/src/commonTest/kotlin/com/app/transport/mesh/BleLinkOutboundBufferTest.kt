package com.app.transport.mesh

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Host tests for the commonMain per-link outbound buffer — the core of the frame-loss fix. Drives a
 * fake radio that can report [ChunkWriteResult.BUSY]/[ChunkWriteResult.GONE] on demand and records
 * every chunk it accepts, so busy→retry→delivery, strict chunk order, contiguity of two frames on
 * one link, and priority-aware overflow are all verified without a real BLE stack.
 */
class BleLinkOutboundBufferTest {

    /** Programmable fake link writer. */
    private class FakeWriter : BleChunkWriter {
        val sent = mutableListOf<ByteArray>()
        /** How many more chunks to accept before reporting BUSY; -1 = always accept. */
        var acceptBudget: Int = -1
        var goneNow: Boolean = false

        override fun writeChunk(chunk: ByteArray): ChunkWriteResult {
            if (goneNow) return ChunkWriteResult.GONE
            if (acceptBudget == 0) return ChunkWriteResult.BUSY
            if (acceptBudget > 0) acceptBudget--
            sent.add(chunk.copyOf())
            return ChunkWriteResult.SENT
        }
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun frame(size: Int, base: Int = 0) = ByteArray(size) { (base + it).toByte() }

    @Test
    fun idleLinkFlushesAllChunksSynchronously() {
        var drops = 0
        val buf = BleLinkOutboundBuffer { drops++ }
        val writer = FakeWriter()

        // 45 bytes, maxChunk 20 -> chunks of 20/20/5, all sent inside submit().
        val accepted = buf.submit(frame(45), maxChunkBytes = 20, writer = writer, priority = BleOutboundPriority.OWN_HIGH, capBytes = 1_000_000)

        assertTrue(accepted)
        assertTrue(buf.isEmpty)
        assertEquals(listOf(20, 20, 5), writer.sent.map { it.size })
        assertContentEquals(frame(45), writer.sent.reduce { a, b -> a + b })
        assertEquals(0, drops)
    }

    @Test
    fun busyMidRunQueuesTailAndReadinessDrainsIt() {
        val buf = BleLinkOutboundBuffer { }
        val writer = FakeWriter().apply { acceptBudget = 1 } // accept 1 chunk then BUSY

        buf.submit(frame(45), maxChunkBytes = 20, writer = writer, priority = BleOutboundPriority.OWN_HIGH, capBytes = 1_000_000)

        assertEquals(1, writer.sent.size)      // only the head chunk went out
        assertFalse(buf.isEmpty)               // tail is queued, not lost

        writer.acceptBudget = -1               // stack ready again
        val stillPending = buf.drain()

        assertFalse(stillPending)
        assertTrue(buf.isEmpty)
        assertEquals(listOf(20, 20, 5), writer.sent.map { it.size }) // strict order preserved
        assertContentEquals(frame(45), writer.sent.reduce { a, b -> a + b })
    }

    @Test
    fun secondFrameWhileStalledStaysContiguousAfterFirst() {
        val buf = BleLinkOutboundBuffer { }
        val writer = FakeWriter().apply { acceptBudget = 1 } // frame A: head out, tail stalls

        val a = frame(45, base = 0)
        val b = frame(45, base = 100)
        buf.submit(a, 20, writer, BleOutboundPriority.OWN_HIGH, 1_000_000)
        // B arrives while A is draining -> must be appended AFTER A's tail, never interleaved.
        buf.submit(b, 20, writer, BleOutboundPriority.OWN_HIGH, 1_000_000)

        writer.acceptBudget = -1
        buf.drain()

        val stream = writer.sent.reduce { x, y -> x + y }
        assertContentEquals(a + b, stream) // A fully, then B fully
    }

    @Test
    fun overflowDropsLowestPriorityNewestAndCounts() {
        var drops = 0
        val buf = BleLinkOutboundBuffer { drops++ }
        val writer = FakeWriter().apply { acceptBudget = 0 } // BUSY immediately -> everything queues

        // First submit stalls at chunk 0 -> the frame becomes the in-flight started frame.
        buf.submit(frame(10, base = 0), maxChunkBytes = 20, writer = writer, priority = BleOutboundPriority.OWN_HIGH, capBytes = 25)
        // Now queue behind it: one relay (low prio) then one own (high prio). Cap 25 bytes forces a drop.
        buf.submit(frame(10, base = 50), 20, writer, BleOutboundPriority.RELAY_BULK, capBytes = 25)
        buf.submit(frame(10, base = 90), 20, writer, BleOutboundPriority.OWN_HIGH, capBytes = 25)

        assertTrue(drops >= 1)

        // Drain everything; the surviving frames must include the started frame and the own frame,
        // and the reduced total must not contain the dropped relay frame's bytes.
        writer.acceptBudget = -1
        buf.drain()
        val stream = writer.sent.reduce { a, b -> a + b }
        assertTrue(stream.toList().containsAll(frame(10, base = 0).toList()))  // started frame kept
        assertTrue(stream.toList().containsAll(frame(10, base = 90).toList())) // own frame kept
        assertFalse(stream.toList().containsAll(frame(10, base = 50).toList())) // relay frame dropped
    }

    @Test
    fun goneClearsBufferAndStopsDraining() {
        val buf = BleLinkOutboundBuffer { }
        val writer = FakeWriter().apply { acceptBudget = 1 }
        buf.submit(frame(60), 20, writer, BleOutboundPriority.OWN_HIGH, 1_000_000)
        assertFalse(buf.isEmpty)

        writer.goneNow = true
        val stillPending = buf.drain()

        assertFalse(stillPending)
        assertTrue(buf.isEmpty) // link gone -> buffer cleared, nothing retried forever
    }

    @Test
    fun singleChunkFrameUnderMtuGoesOutWhole() {
        val buf = BleLinkOutboundBuffer { }
        val writer = FakeWriter()
        val f = bytes(1, 2, 3, 4, 5)
        buf.submit(f, maxChunkBytes = 512, writer = writer, priority = BleOutboundPriority.OWN_HIGH, capBytes = 1_000_000)
        assertEquals(1, writer.sent.size)
        assertContentEquals(f, writer.sent[0])
    }
}
