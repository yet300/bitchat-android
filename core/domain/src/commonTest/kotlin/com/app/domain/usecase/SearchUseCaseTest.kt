@file:OptIn(ExperimentalTime::class)

package com.app.domain.usecase

import com.app.domain.FakeSearchRepository
import com.app.domain.model.BitMessage
import com.app.domain.model.ConversationId
import com.app.domain.model.SenderRef
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class SearchUseCaseTest {

    private val msg = BitMessage(
        id = "1",
        conversationId = ConversationId.PublicMesh,
        sender = SenderRef.SYSTEM,
        content = "hello",
        timestamp = Instant.fromEpochMilliseconds(0),
    )

    @Test fun `blank query short-circuits to empty without hitting repo`() = runTest {
        val repo = FakeSearchRepository(messages = listOf(msg))
        val result = SearchUseCase(repo).invoke("   ")

        assertTrue(result.isEmpty)
        assertTrue(repo.queries.isEmpty())
    }

    @Test fun `aggregates results for a real query`() = runTest {
        val repo = FakeSearchRepository(messages = listOf(msg))
        val result = SearchUseCase(repo).invoke("hel")

        assertEquals(listOf(msg), result.messages)
        assertEquals(listOf("hel"), repo.queries)
    }
}
