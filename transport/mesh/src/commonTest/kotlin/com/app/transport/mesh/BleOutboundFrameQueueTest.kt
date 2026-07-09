package com.app.transport.mesh

import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.SpecialRecipients
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Host tests for the bounded, priority-ordered frame queue that replaced `Channel(UNLIMITED)` in
 * [BleSendCore]: highest-priority-first dequeue, FIFO within a priority, and lowest-priority-newest
 * drop on overflow with telemetry.
 */
class BleOutboundFrameQueueTest {

    private fun routed(tag: ULong): RoutedPacket = RoutedPacket(
        BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = ByteArray(8) { 1 },
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = tag,
            payload = ByteArray(0),
            ttl = 5u,
        ),
    )

    private fun tagOf(r: RoutedPacket): ULong = r.packet.timestamp

    @Test
    fun dequeuesHighestPriorityFirst() = runTest {
        val q = BleOutboundFrameQueue(capacity = 16, onDrop = {})
        q.offer(routed(1u), BleOutboundPriority.RELAY_BULK)
        q.offer(routed(2u), BleOutboundPriority.OWN_HIGH)
        q.offer(routed(3u), BleOutboundPriority.RELAY_HIGH)

        assertEquals(2uL, tagOf(q.receive())) // OWN_HIGH
        assertEquals(3uL, tagOf(q.receive())) // RELAY_HIGH
        assertEquals(1uL, tagOf(q.receive())) // RELAY_BULK
        q.close()
    }

    @Test
    fun fifoWithinSamePriority() = runTest {
        val q = BleOutboundFrameQueue(capacity = 16, onDrop = {})
        q.offer(routed(10u), BleOutboundPriority.OWN_HIGH)
        q.offer(routed(11u), BleOutboundPriority.OWN_HIGH)
        q.offer(routed(12u), BleOutboundPriority.OWN_HIGH)

        assertEquals(10uL, tagOf(q.receive()))
        assertEquals(11uL, tagOf(q.receive()))
        assertEquals(12uL, tagOf(q.receive()))
        q.close()
    }

    @Test
    fun overflowDropsLowestPriorityNewestAndCounts() = runTest {
        var drops = 0
        val q = BleOutboundFrameQueue(capacity = 2, onDrop = { drops++ })
        q.offer(routed(1u), BleOutboundPriority.OWN_HIGH)
        q.offer(routed(2u), BleOutboundPriority.RELAY_BULK)
        q.offer(routed(3u), BleOutboundPriority.RELAY_BULK) // overflow -> drop the newest relay (tag 3)

        assertEquals(1, drops)
        assertEquals(1uL, tagOf(q.receive())) // own kept
        assertEquals(2uL, tagOf(q.receive())) // first relay kept
        q.close()
    }

    @Test
    fun ownTrafficSurvivesARelayStorm() = runTest {
        var drops = 0
        val q = BleOutboundFrameQueue(capacity = 4, onDrop = { drops++ })
        q.offer(routed(100u), BleOutboundPriority.OWN_HIGH) // our interactive frame
        repeat(20) { q.offer(routed((it + 1).toULong()), BleOutboundPriority.RELAY_BULK) }

        assertTrue(drops >= 1)
        assertEquals(100uL, tagOf(q.receive())) // our frame is never the one dropped
        q.close()
    }
}
