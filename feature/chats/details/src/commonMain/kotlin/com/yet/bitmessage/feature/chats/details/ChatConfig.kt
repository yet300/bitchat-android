package com.yet.bitmessage.feature.chats.details

import com.app.domain.model.ConversationId
import com.app.domain.model.GeohashChannel
import com.app.domain.model.GeohashLevel
import com.app.domain.model.PeerId
import kotlinx.serialization.Serializable

/**
 * Serializable navigation configuration mirroring [ConversationId].
 *
 * Domain models stay serialization-free (the Dependency Rule keeps :core:domain pure),
 * so navigation carries this flat mirror and maps back at the component boundary.
 */
@Serializable
sealed interface ChatConfig {

    @Serializable
    data object PublicMesh : ChatConfig

    @Serializable
    data class Channel(val tag: String) : ChatConfig

    @Serializable
    data class Private(val peerRaw: String) : ChatConfig

    @Serializable
    data class Geohash(val level: GeohashLevel, val geohash: String) : ChatConfig

    companion object {
        fun from(id: ConversationId): ChatConfig = when (id) {
            is ConversationId.PublicMesh -> PublicMesh
            is ConversationId.Channel -> Channel(id.tag)
            is ConversationId.Private -> Private(id.peer.raw)
            is ConversationId.Geohash -> Geohash(id.channel.level, id.channel.geohash)
        }
    }

    fun toConversationId(): ConversationId = when (this) {
        is PublicMesh -> ConversationId.PublicMesh
        is Channel -> ConversationId.Channel(tag)
        is Private -> ConversationId.Private(PeerId(peerRaw))
        is Geohash -> ConversationId.Geohash(GeohashChannel(level, geohash))
    }
}
