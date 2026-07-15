package com.app.transport.mesh

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P0.6: GONE must not be reported as a successful accept. */
class BleLinkOutboundGoneTest {

    @Test
    fun submit_returnsFalseWhenLinkGoneOnFirstChunk() {
        val buffer = BleLinkOutboundBuffer(onDrop = {})
        val writer = BleChunkWriter { ChunkWriteResult.GONE }
        val ok = buffer.submit(
            frame = ByteArray(32) { 1 },
            maxChunkBytes = 20,
            writer = writer,
            priority = BleOutboundPriority.RELAY_HIGH,
            capBytes = 1_000_000,
        )
        assertFalse(ok)
    }

    @Test
    fun submit_returnsTrueWhenAllChunksSent() {
        val buffer = BleLinkOutboundBuffer(onDrop = {})
        val writer = BleChunkWriter { ChunkWriteResult.SENT }
        val ok = buffer.submit(
            frame = ByteArray(10) { 1 },
            maxChunkBytes = 64,
            writer = writer,
            priority = BleOutboundPriority.OWN_HIGH,
            capBytes = 1_000_000,
        )
        assertTrue(ok)
        assertTrue(buffer.isEmpty)
    }
}
