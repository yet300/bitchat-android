package com.yet.bitmessage.feature.chats.details

import com.app.domain.model.ConversationId
import com.app.domain.model.GeohashChannel
import com.app.domain.model.GeohashLevel
import com.app.domain.model.PeerId
import kotlinx.serialization.Serializable

/**
 * Serializable navigation configuration for a chat: which [Conversation] to open, plus an optional
 * [targetMessageId] to scroll to / highlight (used by Messages search results).
 *
 * Domain models stay serialization-free (the Dependency Rule keeps :core:domain pure), so navigation
 * carries this flat mirror and maps back at the component boundary.
 */
@Serializable
data class ChatConfig(
    val conversation: Conversation,
    val targetMessageId: String? = null,
) {

    /** Flat, serializable mirror of [ConversationId]. */
    @Serializable
    sealed interface Conversation {
        @Serializable
        data object PublicMesh : Conversation

        @Serializable
        data class Channel(val tag: String) : Conversation

        @Serializable
        data class Private(val peerRaw: String) : Conversation

        @Serializable
        data class Geohash(val level: GeohashLevel, val geohash: String) : Conversation
    }

    fun toConversationId(): ConversationId = when (val c = conversation) {
        is Conversation.PublicMesh -> ConversationId.PublicMesh
        is Conversation.Channel -> ConversationId.Channel(c.tag)
        is Conversation.Private -> ConversationId.Private(PeerId(c.peerRaw))
        is Conversation.Geohash -> ConversationId.Geohash(GeohashChannel(c.level, c.geohash))
    }

    companion object {
        fun from(id: ConversationId, targetMessageId: String? = null): ChatConfig =
            ChatConfig(conversation = conversationOf(id), targetMessageId = targetMessageId)

        private fun conversationOf(id: ConversationId): Conversation = when (id) {
            is ConversationId.PublicMesh -> Conversation.PublicMesh
            is ConversationId.Channel -> Conversation.Channel(id.tag)
            is ConversationId.Private -> Conversation.Private(id.peer.raw)
            is ConversationId.Geohash -> Conversation.Geohash(id.channel.level, id.channel.geohash)
        }
    }
}
