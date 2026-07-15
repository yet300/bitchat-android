package com.app.transport.mesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BleNoiseSessionQueuesTest {

    @Test
    fun privateMessages_fifoTakeAndEmpty() {
        val q = BleNoiseSessionQueues()
        q.appendPrivateMessage("a", "id-a", "peer1")
        q.appendPrivateMessage("b", "id-b", "peer1")
        assertFalse(q.isEmpty)

        val taken = q.takePrivateMessages("peer1")
        assertEquals(
            listOf(
                BleNoiseSessionQueues.PendingPrivateMessage("a", "id-a"),
                BleNoiseSessionQueues.PendingPrivateMessage("b", "id-b"),
            ),
            taken,
        )
        assertTrue(q.takePrivateMessages("peer1").isEmpty())
        assertTrue(q.isEmpty)
    }

    @Test
    fun prependRestoresFailedMessagesAtFront() {
        val q = BleNoiseSessionQueues()
        q.appendPrivateMessage("new", "id-new", "peer1")
        val failed = listOf(
            BleNoiseSessionQueues.PendingPrivateMessage("old1", "id-1"),
            BleNoiseSessionQueues.PendingPrivateMessage("old2", "id-2"),
        )
        q.prependPrivateMessages(failed, "peer1")
        val taken = q.takePrivateMessages("peer1")
        assertEquals(listOf("old1", "old2", "new"), taken.map { it.content })
    }

    @Test
    fun typedPayloads_areCopiedOnAppend() {
        val q = BleNoiseSessionQueues()
        val buf = byteArrayOf(1, 2, 3)
        q.appendTypedPayload(buf, "peer1")
        buf[0] = 9
        val taken = q.takeTypedPayloads("peer1")
        assertEquals(1, taken.size)
        assertEquals(byteArrayOf(1, 2, 3).toList(), taken[0].toList())
    }

    @Test
    fun prependRestoresFailedTypedPayloadsAheadOfNewTraffic() {
        val q = BleNoiseSessionQueues()
        q.appendTypedPayload(byteArrayOf(3), "peer1")

        q.prependTypedPayloads(listOf(byteArrayOf(1), byteArrayOf(2)), "peer1")

        assertEquals(
            listOf(listOf<Byte>(1), listOf<Byte>(2), listOf<Byte>(3)),
            q.takeTypedPayloads("peer1").map(ByteArray::toList),
        )
    }

    @Test
    fun perPeerCapDropsOldestPrivateMessage() {
        val q = BleNoiseSessionQueues(maxPrivateMessagesPerPeer = 2, maxTypedPayloadsPerPeer = 2, maxPeers = 8)
        q.appendPrivateMessage("1", "id1", "peer1")
        q.appendPrivateMessage("2", "id2", "peer1")
        q.appendPrivateMessage("3", "id3", "peer1")
        val taken = q.takePrivateMessages("peer1")
        assertEquals(listOf("2", "3"), taken.map { it.content })
    }

    @Test
    fun peerCapEvictsEldestPeer() {
        val q = BleNoiseSessionQueues(maxPrivateMessagesPerPeer = 4, maxTypedPayloadsPerPeer = 4, maxPeers = 2)
        q.appendPrivateMessage("a", "ida", "peerA")
        q.appendPrivateMessage("b", "idb", "peerB")
        q.appendPrivateMessage("c", "idc", "peerC") // evicts peerA
        assertTrue(q.takePrivateMessages("peerA").isEmpty())
        assertEquals(listOf("b"), q.takePrivateMessages("peerB").map { it.content })
        assertEquals(listOf("c"), q.takePrivateMessages("peerC").map { it.content })
    }

    @Test
    fun clearEmptiesBothQueues() {
        val q = BleNoiseSessionQueues()
        q.appendPrivateMessage("hi", "id", "peer1")
        q.appendTypedPayload(byteArrayOf(0x01), "peer1")
        q.clear()
        assertTrue(q.isEmpty)
        assertTrue(q.takePrivateMessages("peer1").isEmpty())
        assertTrue(q.takeTypedPayloads("peer1").isEmpty())
    }
}
