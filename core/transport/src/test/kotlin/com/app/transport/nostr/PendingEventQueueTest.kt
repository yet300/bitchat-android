package com.app.transport.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingEventQueueTest {

    private val relayA = "wss://relay-a.example"
    private val relayB = "wss://relay-b.example"
    private val relayC = "wss://relay-c.example"

    @Test
    fun eventToFullyConnectedRelaysLeavesQueueEmpty() {
        val queue = PendingEventQueue<String>()

        val result = queue.submit("event1", listOf(relayA, relayB)) { true }

        assertEquals(listOf(relayA, relayB), result.sendNow)
        assertTrue(result.dropped.isEmpty())
        assertEquals(0, queue.size())
    }

    @Test
    fun eventToOneDeadRelayStaysQueuedOnlyForThatRelay() {
        val queue = PendingEventQueue<String>()

        val result = queue.submit("event1", listOf(relayA, relayB, relayC)) { it != relayB }

        assertEquals(listOf(relayA, relayC), result.sendNow)
        assertEquals(1, queue.size())
        assertEquals(listOf(relayB), queue.pendingRelaysOf("event1"))

        // Unrelated relay reconnect must not deliver (or evict) the entry
        assertTrue(queue.drainFor(relayA).isEmpty())
        assertEquals(1, queue.size())

        // The dead relay reconnects: event delivered exactly there, entry removed
        assertEquals(listOf("event1"), queue.drainFor(relayB))
        assertEquals(0, queue.size())

        // Second reconnect of the same relay must not re-deliver
        assertTrue(queue.drainFor(relayB).isEmpty())
    }

    @Test
    fun drainPreservesSubmissionOrder() {
        val queue = PendingEventQueue<String>()
        queue.submit("first", listOf(relayA)) { false }
        queue.submit("second", listOf(relayA, relayB)) { false }

        assertEquals(listOf("first", "second"), queue.drainFor(relayA))
        // "second" still waits for relayB
        assertEquals(1, queue.size())
        assertEquals(listOf(relayB), queue.pendingRelaysOf("second"))
    }

    @Test
    fun capDropsOldestEntries() {
        val queue = PendingEventQueue<String>(maxEntries = 2)
        queue.submit("e1", listOf(relayA)) { false }
        queue.submit("e2", listOf(relayA)) { false }

        val result = queue.submit("e3", listOf(relayA)) { false }

        assertEquals(listOf("e1"), result.dropped)
        assertEquals(2, queue.size())
        assertNull(queue.pendingRelaysOf("e1"))
        assertEquals(listOf("e2", "e3"), queue.drainFor(relayA))
    }

    @Test
    fun clearEmptiesQueue() {
        val queue = PendingEventQueue<String>()
        queue.submit("e1", listOf(relayA)) { false }

        queue.clear()

        assertEquals(0, queue.size())
        assertTrue(queue.drainFor(relayA).isEmpty())
    }
}
