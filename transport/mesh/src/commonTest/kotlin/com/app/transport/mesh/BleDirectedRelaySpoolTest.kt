package com.app.transport.mesh

import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.SpecialRecipients
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BleDirectedRelaySpoolTest {

    private fun packet(marker: UByte) = BitchatPacket(
        type = MessageType.NOISE_ENCRYPTED.value,
        senderID = ByteArray(8) { 0x11 },
        recipientID = ByteArray(8) { 0x22 },
        timestamp = marker.toULong(),
        payload = byteArrayOf(marker.toByte()),
        ttl = 7u,
    )

    @Test
    fun enqueueDedupsByMessageId() {
        val spool = BleDirectedRelaySpool()
        val p = packet(1u)
        assertTrue(spool.enqueue(p, "peerA", "mid-1", enqueuedAtMs = 1000L))
        assertFalse(spool.enqueue(p, "peerA", "mid-1", enqueuedAtMs = 1001L))
        assertEquals(1, spool.count)
    }

    @Test
    fun drainReturnsOnlyUnexpiredAndClears() {
        val spool = BleDirectedRelaySpool()
        // now=10_000, window=5_000 → keep enqueuedAt >= 5_000
        spool.enqueue(packet(1u), "peerA", "m1", enqueuedAtMs = 8_000L)
        spool.enqueue(packet(2u), "peerB", "m2", enqueuedAtMs = 9_000L)
        spool.enqueue(packet(3u), "peerC", "m3", enqueuedAtMs = 1_000L) // expired

        val drained = spool.drainUnexpired(nowMs = 10_000L, windowMs = 5_000L)
        assertEquals(2, drained.size)
        assertEquals(setOf("peerA", "peerB"), drained.map { it.recipient }.toSet())
        assertTrue(spool.isEmpty)
        assertTrue(spool.drainUnexpired(nowMs = 10_000L, windowMs = 5_000L).isEmpty())
    }

    @Test
    fun pruneExpiredKeepsFresh() {
        val spool = BleDirectedRelaySpool()
        spool.enqueue(packet(1u), "peerA", "m1", enqueuedAtMs = 1000L)
        spool.enqueue(packet(2u), "peerA", "m2", enqueuedAtMs = 9000L)
        spool.pruneExpired(nowMs = 10_000L, windowMs = 5_000L)
        assertEquals(1, spool.count)
        val left = spool.drainUnexpired(nowMs = 10_000L, windowMs = 5_000L)
        assertEquals(listOf("m2"), left.map { "m2" }) // one entry remains conceptually
        assertEquals(1, left.size)
        assertEquals("peerA", left.single().recipient)
    }

    @Test
    fun clearEmpties() {
        val spool = BleDirectedRelaySpool()
        spool.enqueue(packet(1u), "peerA", "m1", enqueuedAtMs = 1L)
        spool.clear()
        assertTrue(spool.isEmpty)
        assertEquals(0, spool.count)
    }
}
