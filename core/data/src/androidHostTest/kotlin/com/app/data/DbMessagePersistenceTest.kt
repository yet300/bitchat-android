@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package com.app.data

import com.app.data.repository.InMemoryDatabase
import com.app.transport.model.BitchatMessage
import com.app.transport.model.DeliveryStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class DbMessagePersistenceTest {

    // One dispatcher/scheduler shared by the worker, the DB io, and runTest so the fire-and-forget
    // writes drain deterministically with advanceUntilIdle().
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(dispatcher)
    private val db = InMemoryDatabase(dispatcher)

    private fun persistence() = DbMessagePersistence(db.messageDao, scope)

    private fun msg(id: String, peer: String) = BitchatMessage(
        id = id,
        sender = "sender-$id",
        content = "content-$id",
        timestamp = Instant.fromEpochMilliseconds(1_000L + id.hashCode()),
        senderPeerID = peer,
    )

    @Test
    fun status_update_enqueued_right_after_insert_is_not_lost() = runTest(dispatcher) {
        val persistence = persistence()
        val key = "private:peerB"

        // Interleave persist(id) immediately followed by updateStatus(id): the old per-call-coroutine
        // design could run the status read before the insert committed and drop the status.
        repeat(50) { i ->
            val id = "d$i"
            persistence.persist(key, msg(id, "peerB"))
            persistence.updateStatus(id, DeliveryStatus.Delivered(to = "peerB", at = Clock.System.now()))
        }
        advanceUntilIdle()

        val loaded = persistence.load(500).getValue(key)
        assertEquals(50, loaded.size)
        assertTrue(
            "every message must carry the Delivered status persisted right after its insert",
            loaded.all { it.deliveryStatus is DeliveryStatus.Delivered },
        )
    }

    @Test
    fun a_storm_of_100_messages_collapses_into_a_single_transaction() = runTest(dispatcher) {
        val persistence = persistence()
        var transactions = 0
        var totalCommands = 0
        persistence.onBatchFlushed = { size -> transactions++; totalCommands += size }

        repeat(100) { i -> persistence.persist("public", msg("p$i", "peerA")) }
        advanceUntilIdle()

        assertEquals(100, totalCommands)
        // The worker takes the first command, waits one batch window during which the other 99 pile
        // up, then folds all 100 into one transaction — a handful at most, never one-per-message.
        assertTrue("expected far fewer transactions than messages, was $transactions", transactions <= 2)
        assertEquals(100, persistence.load(500).getValue("public").size)
    }

    @Test
    fun delete_after_persist_removes_the_row_in_order() = runTest(dispatcher) {
        val persistence = persistence()
        persistence.persist("public", msg("p1", "peerA"))
        persistence.persist("public", msg("p2", "peerA"))
        persistence.delete("p1")
        advanceUntilIdle()

        val ids = persistence.load(500)["public"]?.map { it.id } ?: emptyList()
        assertEquals(listOf("p2"), ids)
    }
}
