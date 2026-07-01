@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package com.app.data

import com.app.crypto.EncryptionService
import com.app.data.repository.InMemoryDatabase
import com.app.transport.SeenMessageStore
import com.app.transport.model.BitchatMessage
import com.app.transport.model.DeliveryStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class AppStateStorePersistenceTest {

    // One dispatcher (and thus one test scheduler) shared by the persistence scope, the DB io, and
    // runTest, so fire-and-forget writes can be drained deterministically with advanceUntilIdle().
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(dispatcher)
    private val db = InMemoryDatabase(dispatcher)

    private fun store(persistence: MessagePersistence): AppStateStore {
        val encryption = mock<EncryptionService> { whenever(it.getIdentityFingerprint()).thenReturn("ffffffffffffffff") }
        val seen = mock<SeenMessageStore> { whenever(it.hasRead(any())).thenReturn(false) }
        return AppStateStore(encryption, seen, { FakeContactRepository() }, persistence, scope)
    }

    private fun persistence() = DbMessagePersistence(db.messageDao, scope)

    private fun msg(id: String, content: String, peer: String) = BitchatMessage(
        id = id,
        sender = "sender-$id",
        content = content,
        timestamp = Instant.fromEpochMilliseconds(1_000L),
        senderPeerID = peer,
    )

    @Test
    fun timelines_survive_a_simulated_restart() = runTest(dispatcher) {
        val first = store(persistence())
        first.addPublicMessage(msg("p1", "hello public", "peerA"))
        first.addPrivateMessage("peerB", msg("d1", "hi dm", "peerB"))
        first.addChannelMessage("#general", msg("c1", "in channel", "peerC"))
        advanceUntilIdle()

        // Simulate process restart: a fresh store over the same database.
        val restarted = store(persistence())
        restarted.hydrate()
        advanceUntilIdle()

        assertEquals(listOf("p1"), restarted.publicMessages.value.map { it.id })
        assertEquals(listOf("d1"), restarted.privateMessages.value["peerB"]?.map { it.id })
        assertEquals(listOf("c1"), restarted.channelMessages.value["#general"]?.map { it.id })
        assertEquals("hello public", restarted.publicMessages.value.single().content)
    }

    @Test
    fun delivery_status_persists_across_restart() = runTest(dispatcher) {
        val first = store(persistence())
        first.addPrivateMessage("peerB", msg("d1", "hi", "peerB"))
        first.updateMessageStatus("d1", DeliveryStatus.Delivered(to = "peerB", at = Clock.System.now()))
        advanceUntilIdle()

        val restarted = store(persistence())
        restarted.hydrate()
        advanceUntilIdle()

        val status = restarted.privateMessages.value["peerB"]?.single()?.deliveryStatus
        assertEquals(true, status is DeliveryStatus.Delivered)
    }

    @Test
    fun cleared_conversation_does_not_come_back() = runTest(dispatcher) {
        val first = store(persistence())
        first.addPublicMessage(msg("p1", "hello", "peerA"))
        first.clearPublic()
        advanceUntilIdle()

        val restarted = store(persistence())
        restarted.hydrate()
        advanceUntilIdle()

        assertEquals(emptyList<String>(), restarted.publicMessages.value.map { it.id })
    }
}
