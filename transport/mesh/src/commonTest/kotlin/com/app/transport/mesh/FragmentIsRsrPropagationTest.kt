package com.app.transport.mesh

import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.peerIdToRoutingBytes
import kotlin.test.Test
import kotlin.test.assertTrue

/** P0.2: fragment packets must inherit isRSR from the parent (iOS BLEOutboundFragmentPlanner). */
class FragmentIsRsrPropagationTest {

    @Test
    fun createFragments_copiesIsRsrFlag() {
        val largePayload = ByteArray(800) { it.toByte() }
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = peerIdToRoutingBytes("1122334455667788"),
            recipientID = peerIdToRoutingBytes("8877665544332211"),
            timestamp = 1_700_000_000_000uL,
            payload = largePayload,
            signature = null,
            ttl = 7u,
            isRSR = true,
        )
        val fragments = FragmentManager().createFragments(packet)
        assertTrue(fragments.size > 1, "expected fragmentation for 800-byte payload")
        assertTrue(fragments.all { it.isRSR }, "every fragment must carry isRSR=true")
    }

    @Test
    fun createFragments_defaultIsRsrFalse() {
        val largePayload = ByteArray(800) { it.toByte() }
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = peerIdToRoutingBytes("1122334455667788"),
            recipientID = null,
            timestamp = 1_700_000_000_000uL,
            payload = largePayload,
            signature = null,
            ttl = 7u,
            isRSR = false,
        )
        val fragments = FragmentManager().createFragments(packet)
        assertTrue(fragments.size > 1)
        assertTrue(fragments.none { it.isRSR })
    }
}
