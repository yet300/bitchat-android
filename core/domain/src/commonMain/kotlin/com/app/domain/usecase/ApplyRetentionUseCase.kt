@file:OptIn(ExperimentalTime::class)

package com.app.domain.usecase

import com.app.domain.model.ConversationId
import com.app.domain.model.RetentionPolicy
import com.app.domain.repository.MessageRepository
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Prune a conversation's timeline to its [RetentionPolicy]: removes messages older than the policy's
 * max age. [RetentionPolicy.KEEP_ALL] is a no-op. Ongoing enforcement (messages aging out over time)
 * is the caller's concern — this applies the cut once.
 */
class ApplyRetentionUseCase(
    private val messages: MessageRepository,
    private val clock: Clock = Clock.System,
) {
    suspend operator fun invoke(conversation: ConversationId, policy: RetentionPolicy) {
        val maxAge = policy.maxAgeMillis ?: return
        val cutoff = clock.now().toEpochMilliseconds() - maxAge
        messages.snapshot(conversation)
            .filter { it.timestamp.toEpochMilliseconds() < cutoff }
            .forEach { messages.remove(it.id) }
    }
}
