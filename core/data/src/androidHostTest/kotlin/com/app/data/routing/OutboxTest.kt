@file:OptIn(ExperimentalCoroutinesApi::class)

package com.app.data.routing

import com.app.data.favorites.FavoritesPersistenceService
import com.app.data.repository.InMemoryDatabase
import com.app.database.dao.OutboxDao
import com.app.transport.routing.OutgoingEnvelope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

class OutboxTest {

    private val peer = "aabbccdd11223344"

    private fun outbox(dao: OutboxDao) = Outbox(mock<FavoritesPersistenceService>(), dao)

    @Test
    fun drainReturnsQueuedEnvelopesOnce() = runTest {
        val outbox = outbox(InMemoryDatabase().outboxDao)
        val envelope = OutgoingEnvelope.Receipt(peer, "msg-1")

        outbox.enqueue(envelope)

        assertEquals(listOf<OutgoingEnvelope>(envelope), outbox.drain(peer))
        assertEquals(emptyList<OutgoingEnvelope>(), outbox.drain(peer))
    }

    @Test
    fun everyEnvelopeKindRoundTripsThroughSerialization() = runTest {
        val outbox = outbox(InMemoryDatabase().outboxDao)
        val envelopes = listOf(
            OutgoingEnvelope.Private(peer, "hi", "nick", "m1"),
            OutgoingEnvelope.Receipt(peer, "orig-1"),
            OutgoingEnvelope.Ack(peer, "m2"),
            OutgoingEnvelope.Favorite(peer, isFavorite = true),
        )
        envelopes.forEach { outbox.enqueue(it) }

        assertEquals(envelopes, outbox.drain(peer))
    }

    /** Phase 3 core property: the queue survives process death (a fresh Outbox over the same DB). */
    @Test
    fun queueSurvivesARestart() = runTest {
        val db = InMemoryDatabase()
        val envelope = OutgoingEnvelope.Private(peer, "persist me", "nick", "m1")

        outbox(db.outboxDao).enqueue(envelope)

        // Simulate a restart: a brand-new Outbox instance over the same underlying database.
        val restarted = outbox(db.outboxDao)
        assertEquals(listOf<OutgoingEnvelope>(envelope), restarted.drain(peer))
    }

    @Test
    fun expiredEnvelopesAreNotDelivered() = runTest {
        val db = InMemoryDatabase()
        val outbox = outbox(db.outboxDao).apply { ttlMillis = 1 }

        outbox.enqueue(OutgoingEnvelope.Ack(peer, "stale"))
        // Let wall-clock pass the 1ms TTL before draining (drain purges expired rows first).
        Thread.sleep(5)

        assertEquals(emptyList<OutgoingEnvelope>(), outbox.drain(peer))
        assertEquals(0L, db.outboxDao.count())
    }

    @Test
    fun capEvictsOldestEnvelopes() = runTest {
        val db = InMemoryDatabase()
        val outbox = outbox(db.outboxDao).apply { maxEntries = 3 }

        repeat(5) { i -> outbox.enqueue(OutgoingEnvelope.Ack(peer, "m$i")) }

        // Only the newest 3 survive; the two oldest (m0, m1) were evicted on insert.
        val ids = outbox.drain(peer).filterIsInstance<OutgoingEnvelope.Ack>().map { it.messageId }
        assertEquals(listOf("m2", "m3", "m4"), ids)
    }

    /**
     * Audit A4: interleaved enqueue/drain must never lose or duplicate an envelope. The old shared
     * lock is gone; atomicity now comes from the drain transaction (read-and-delete in one step).
     */
    @Test
    fun interleavedEnqueueAndDrainLosesNothing() = runTest {
        val outbox = outbox(InMemoryDatabase().outboxDao)
        var enqueued = 0
        var drained = 0

        repeat(50) { i ->
            outbox.enqueue(OutgoingEnvelope.Ack(peer, "m$i")); enqueued++
            if (i % 5 == 0) drained += outbox.drain(peer).size
        }
        drained += outbox.drain(peer).size

        assertEquals(enqueued, drained)
        assertTrue("queue must be empty after the final drain", outbox.drain(peer).isEmpty())
    }
}
