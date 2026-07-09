@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package com.app.data

import com.app.data.repository.InMemoryDatabase
import com.app.database.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class MessageRetentionJobTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(dispatcher)
    private val db = InMemoryDatabase(dispatcher)

    private fun job() = MessageRetentionJob(db.messageDao, scope)

    private fun row(id: String, conversation: String, timestamp: Long) = Message(
        id = id,
        conversation_id = conversation,
        sender_peer_id = "peer",
        sender_name = "sender",
        content = "content-$id",
        timestamp = timestamp,
        type = "TEXT",
        is_mine = 0L,
        is_relay = 0L,
        mentions = null,
        delivery_status = null,
        attachment_path = null,
        attachment_type = null,
        pow_difficulty = null,
        channel = null,
        wire_json = null,
    )

    @Test
    fun age_sweep_drops_only_rows_older_than_the_cutoff() = runTest(dispatcher) {
        val now = Clock.System.now().toEpochMilliseconds()
        val day = 24L * 60 * 60 * 1000
        db.messageDao.upsert(row("old", "public", now - 40 * day))
        db.messageDao.upsert(row("recent", "public", now - 1 * day))

        val job = job().apply { maxAgeMillis = 30 * day; perConversationCap = 0 }
        job.sweep()

        val ids = db.messageDao.byConversation("public").map { it.id }
        assertEquals(listOf("recent"), ids)
    }

    @Test
    fun per_conversation_cap_keeps_newest_and_leaves_other_conversations_alone() = runTest(dispatcher) {
        // 5 rows in A (timestamps 1..5), 2 rows in B.
        repeat(5) { i -> db.messageDao.upsert(row("a$i", "convA", (i + 1).toLong())) }
        repeat(2) { i -> db.messageDao.upsert(row("b$i", "convB", (i + 1).toLong())) }

        val job = job().apply { maxAgeMillis = 0; perConversationCap = 3 }
        job.sweep()

        // byConversation returns ORDER BY timestamp ASC — the 3 newest of A are a2,a3,a4.
        assertEquals(listOf("a2", "a3", "a4"), db.messageDao.byConversation("convA").map { it.id })
        assertEquals(listOf("b0", "b1"), db.messageDao.byConversation("convB").map { it.id })
    }
}
