@file:OptIn(ExperimentalTime::class)

package com.app.domain.usecase

import com.app.domain.FakeMessageRepository
import com.app.domain.model.BitMessage
import com.app.domain.model.ConversationId
import com.app.domain.model.RetentionPolicy
import com.app.domain.model.SenderRef
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class ApplyRetentionUseCaseTest {

    private val channel = ConversationId.Channel("#dev")
    private val now = Instant.fromEpochMilliseconds(10L * 24 * 60 * 60 * 1000) // day 10
    private val clock = object : Clock { override fun now(): Instant = now }

    private fun msg(id: String, daysAgo: Long) = BitMessage(
        id = id,
        conversationId = channel,
        sender = SenderRef.SYSTEM,
        content = id,
        timestamp = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - daysAgo * 24 * 60 * 60 * 1000),
    )

    @Test fun keep_all_prunes_nothing() = runTest {
        val repo = FakeMessageRepository().apply {
            store[channel] = mutableListOf(msg("old", daysAgo = 40))
        }
        ApplyRetentionUseCase(repo, clock).invoke(channel, RetentionPolicy.KEEP_ALL)
        assertEquals(emptyList(), repo.removed)
    }

    @Test fun week_policy_removes_messages_older_than_seven_days() = runTest {
        val repo = FakeMessageRepository().apply {
            store[channel] = mutableListOf(msg("fresh", daysAgo = 2), msg("stale", daysAgo = 9))
        }
        ApplyRetentionUseCase(repo, clock).invoke(channel, RetentionPolicy.WEEK)
        assertEquals(listOf("stale"), repo.removed)
    }
}
