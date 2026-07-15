package com.app.transport.mesh

import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.peerIdToRoutingBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BleIngressLinkRegistryTest {

    private fun packet(payload: Byte = 1) = BitchatPacket(
        version = 1u,
        type = MessageType.MESSAGE.value,
        senderID = peerIdToRoutingBytes("1111111111111111"),
        recipientID = null,
        timestamp = 1_700_000_000_000uL,
        payload = byteArrayOf(payload),
        signature = null,
        ttl = 7u,
    )

    private fun packet(index: Int) = BitchatPacket(
        version = 1u,
        type = MessageType.MESSAGE.value,
        senderID = peerIdToRoutingBytes("1111111111111111"),
        recipientID = null,
        timestamp = (1_700_000_000_000L + index).toULong(),
        payload = byteArrayOf((index % 251).toByte()),
        signature = null,
        ttl = 7u,
    )

    @Test
    fun recordIfNew_dedupsWithinLifetime() {
        val reg = BleIngressLinkRegistry()
        val p = packet()
        val link = BleIngressLinkId.Peripheral("aa")
        assertTrue(reg.recordIfNew(p, link, "peerA", nowMs = 1_000L, lifetimeMs = 3_000L))
        assertFalse(reg.recordIfNew(p, link, "peerA", nowMs = 2_000L, lifetimeMs = 3_000L))
        // After lifetime, same message may be recorded again.
        assertTrue(reg.recordIfNew(p, link, "peerA", nowMs = 5_000L, lifetimeMs = 3_000L))
    }

    @Test
    fun messageIdStableForSamePacket() {
        val p = packet(7)
        assertEquals(BleIngressLinkRegistry.messageId(p), BleIngressLinkRegistry.messageId(p))
    }

    @Test
    fun messageIdDiffersWhenPayloadDiffers() {
        assertTrue(
            BleIngressLinkRegistry.messageId(packet(1)) !=
                BleIngressLinkRegistry.messageId(packet(2)),
        )
    }

    @Test
    fun pruneRemovesOldRecords() {
        val reg = BleIngressLinkRegistry()
        reg.recordIfNew(packet(1), BleIngressLinkId.Central("c1"), "peerA", 1_000L, 3_000L)
        reg.prune(beforeMs = 2_000L)
        assertTrue(reg.isEmpty)
    }

    @Test
    fun recordIfNew_hardCapEvictsOldestWhenAllFresh() {
        val reg = BleIngressLinkRegistry()
        val link = BleIngressLinkId.Peripheral("aa")
        // lifetime huge so nothing is expired; still must not grow past MAX forever.
        val lifetime = 1_000_000_000L
        // Insert more than MAX_RECORDS with distinct payloads (via different timestamps).
        val max = 4096
        repeat(max + 50) { i ->
            val p = packet(i)
            reg.recordIfNew(p, link, "peerA", nowMs = 1_700_000_000_000L + i, lifetimeMs = lifetime)
        }
        assertEquals(max, reg.debugRecordCount())
        val first = packet(0)
        // If hard-capped correctly, FIFO removed the oldest insertion so it is new again.
        assertTrue(
            reg.recordIfNew(first, link, "peerA", nowMs = 1_700_000_000_000L + max + 100, lifetimeMs = lifetime),
            "oldest insertion must be FIFO-evicted when over MAX_RECORDS",
        )
        assertEquals(max, reg.debugRecordCount())
    }

    @Test
    fun hardCapPrunesExpiredBeforeFreshEntries() {
        val reg = BleIngressLinkRegistry()
        val link = BleIngressLinkId.Peripheral("aa")
        val expired = packet(0)
        reg.recordIfNew(expired, link, "peerA", nowMs = 0L, lifetimeMs = 100L)

        repeat(4096) { i ->
            reg.recordIfNew(packet(i + 1), link, "peerA", nowMs = 1_000L, lifetimeMs = 100L)
        }

        assertEquals(4096, reg.debugRecordCount())
        assertEquals(null, reg.record(expired))
        assertTrue(reg.record(packet(1)) != null)
    }

    @Test
    fun readsAndRecentDuplicatesDoNotChangeFifoEvictionOrder() {
        val reg = BleIngressLinkRegistry()
        val link = BleIngressLinkId.Peripheral("aa")
        val lifetime = Long.MAX_VALUE
        repeat(4096) { i -> reg.recordIfNew(packet(i), link, "peerA", i.toLong(), lifetime) }

        assertTrue(reg.record(packet(0)) != null)
        assertFalse(reg.recordIfNew(packet(0), link, "peerA", 5_000L, lifetime))
        reg.recordIfNew(packet(4096), link, "peerA", 5_001L, lifetime)

        assertEquals(null, reg.record(packet(0)))
        assertTrue(reg.record(packet(1)) != null)
        assertEquals(4096, reg.debugRecordCount())
    }

    @Test
    fun expiredRerecordMovesEntryToNewestFifoPosition() {
        val reg = BleIngressLinkRegistry()
        val link = BleIngressLinkId.Peripheral("aa")
        repeat(4096) { i -> reg.recordIfNew(packet(i), link, "peerA", nowMs = 0L, lifetimeMs = 100L) }

        assertTrue(reg.recordIfNew(packet(0), link, "peerA", nowMs = 1_000L, lifetimeMs = 100L))
        reg.recordIfNew(packet(4096), link, "peerA", nowMs = 1_001L, lifetimeMs = 10_000L)

        assertTrue(reg.record(packet(0)) != null, "re-recorded entry is a new FIFO insertion")
        assertEquals(null, reg.record(packet(1)), "oldest unchanged insertion is evicted first")
        assertEquals(4096, reg.debugRecordCount())
    }
}
