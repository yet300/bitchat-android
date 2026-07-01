package com.app.data.routing

import com.app.data.favorites.FavoritesPersistenceService
import com.app.transport.routing.OutgoingEnvelope
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OutboxTest {

    private val peer = "aabbccdd11223344"

    @Test
    fun drainReturnsQueuedEnvelopesOnce() {
        val outbox = Outbox(mock<FavoritesPersistenceService>())
        val envelope = OutgoingEnvelope.Receipt(peer, "msg-1")

        outbox.enqueue(envelope)

        assertEquals(listOf<OutgoingEnvelope>(envelope), outbox.drain(peer))
        assertEquals(emptyList<OutgoingEnvelope>(), outbox.drain(peer))
    }

    /**
     * Audit A4 regression: concurrent enqueue/drain must never lose an envelope
     * (enqueue into a list a drain already removed from the map).
     */
    @Test
    fun concurrentEnqueueAndDrainLosesNothing() {
        val outbox = Outbox(mock<FavoritesPersistenceService>())
        val producers = 4
        val perProducer = 500
        val total = producers * perProducer
        val drained = AtomicInteger(0)
        val start = CountDownLatch(1)
        val done = CountDownLatch(producers)
        val pool = Executors.newFixedThreadPool(producers + 1)

        repeat(producers) { p ->
            pool.execute {
                start.await()
                repeat(perProducer) { i ->
                    outbox.enqueue(OutgoingEnvelope.Ack(peer, "p$p-m$i"))
                }
                done.countDown()
            }
        }
        pool.execute {
            start.await()
            while (done.count > 0 || drained.get() < total) {
                drained.addAndGet(outbox.drain(peer).size)
                if (done.count == 0L && drained.get() >= total) break
            }
        }

        start.countDown()
        check(done.await(10, TimeUnit.SECONDS)) { "producers did not finish" }
        // Final sweep for anything enqueued after the drainer's last pass
        var spins = 0
        while (drained.get() < total && spins++ < 100) {
            drained.addAndGet(outbox.drain(peer).size)
            Thread.sleep(1)
        }
        pool.shutdownNow()

        assertEquals("every enqueued envelope must be drained exactly once", total, drained.get())
    }
}
