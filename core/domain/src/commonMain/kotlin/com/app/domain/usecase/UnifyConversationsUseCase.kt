package com.app.domain.usecase

import com.app.domain.model.ConversationId
import com.app.domain.model.PeerId
import com.app.domain.repository.MessageRepository

/**
 * Merge several private conversations (held under alias keys — ephemeral mesh id / stable Noise
 * key / Nostr alias) into one canonical peer's conversation, so there is a single chat per
 * identity. Source conversations are cleared after merging. Port of
 * ConversationAliasResolver.unifyChatsIntoPeer.
 */
class UnifyConversationsUseCase(
    private val messages: MessageRepository,
) {
    suspend operator fun invoke(target: PeerId, sources: List<PeerId>) {
        val targetId = ConversationId.Private(target)
        sources.forEach { source ->
            if (source == target) return@forEach
            val sourceId = ConversationId.Private(source)
            val merged = messages.snapshot(sourceId)
            if (merged.isNotEmpty()) {
                merged.forEach { messages.append(targetId, it) }
                messages.clear(sourceId)
            }
        }
    }
}
