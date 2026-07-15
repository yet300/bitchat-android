package com.app.transport.mesh

import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.peerIdToRoutingBytes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P1: self-authored fragments skip assembly (iOS BLEFragmentHandler).
 * Policy unit test + remote assembly regression.
 */
class SelfAuthoredFragmentSuppressionTest {

    private val local = "0a0b0c0d0e0f1011"
    private val remote = "bbbbbbbbbbbbbbbb"

    @Test
    fun shouldAssemble_falseForSelfAuthor() {
        assertFalse(BleFragmentIngressPolicy.shouldAssemble(local, local))
        assertFalse(BleFragmentIngressPolicy.shouldAssemble(null, local))
    }

    @Test
    fun shouldAssemble_trueForRemoteAuthor() {
        assertTrue(BleFragmentIngressPolicy.shouldAssemble(remote, local))
    }

    @Test
    fun remoteFragmentsStillAssemble() {
        val fm = FragmentManager()
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = peerIdToRoutingBytes(remote),
            recipientID = null,
            timestamp = 1_700_000_000_000uL,
            payload = ByteArray(800) { it.toByte() },
            signature = null,
            ttl = 0u,
            isRSR = true,
        )
        val fragments = fm.createFragments(packet)
        assertTrue(fragments.size > 1)
        var done: BitchatPacket? = null
        for (f in fragments) {
            val r = fm.handleFragment(f)
            if (r != null) done = r
        }
        assertNotNull(done)
    }
}
