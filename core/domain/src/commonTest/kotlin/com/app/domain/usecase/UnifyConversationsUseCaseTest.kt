@file:OptIn(ExperimentalTime::class)

package com.app.domain.usecase

import com.app.domain.FakeMessageRepository
import com.app.domain.model.BitMessage
import com.app.domain.model.ConversationId
import com.app.domain.model.PeerId
import com.app.domain.model.SenderRef
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class UnifyConversationsUseCaseTest {

    private val target = PeerId("1111111111111111")
    private val source = PeerId("a".repeat(64)) // stable noise key alias

    private fun msg(id: String) = BitMessage(
        id = id,
        conversationId = ConversationId.Private(source),
        sender = SenderRef.SYSTEM,
        content = "c$id",
        timestamp = Instant.fromEpochMilliseconds(0),
    )

    @Test fun `merges source messages into target and clears source`() = runTest {
        val repo = FakeMessageRepository()
        repo.append(ConversationId.Private(source), msg("1"))
        repo.append(ConversationId.Private(source), msg("2"))
        repo.appended.clear()

        UnifyConversationsUseCase(repo).invoke(target, listOf(source))

        assertEquals(2, repo.snapshot(ConversationId.Private(target)).size)
        assertTrue(repo.snapshot(ConversationId.Private(source)).isEmpty())
    }

    @Test fun `target is not merged into itself`() = runTest {
        val repo = FakeMessageRepository()
        repo.append(ConversationId.Private(target), msg("1"))

        UnifyConversationsUseCase(repo).invoke(target, listOf(target))

        // Untouched: still exactly one message, not duplicated or cleared.
        assertEquals(1, repo.snapshot(ConversationId.Private(target)).size)
    }
}
