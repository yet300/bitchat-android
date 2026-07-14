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
}
