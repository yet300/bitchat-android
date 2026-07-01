package com.app.data.repository

import com.app.domain.model.ConversationId
import com.app.domain.model.GeohashChannel
import com.app.domain.model.GeohashLevel
import com.app.domain.model.PeerId

/**
 * Single source of truth for the pin/mute + global-mute settings keys and the [ConversationId]
 * encoding. Shared by [ConversationPrefsRepositoryImpl] (writes/reads the sets), the
 * [NotificationSettingsRepositoryImpl] global-mute flag, and [NotificationMutePolicyImpl]
 * (suppression), so the conversation-list mute and the notification suppression always agree on
 * the key — no drift between the surfaces that set and consume mute.
 */
internal object MutePrefsKeys {

    const val PINNED = "pinned_conversations"
    const val MUTED = "muted_conversations"
    const val GLOBAL_MUTE = "notifications_global_mute"
    const val SEPARATOR = "\n"

    /** Stable, newline-safe key for a conversation (ids never contain a newline). */
    fun encode(id: ConversationId): String = when (id) {
        ConversationId.PublicMesh -> "mesh"
        is ConversationId.Channel -> "ch:${id.tag}"
        is ConversationId.Private -> "pm:${id.peer.raw}"
        is ConversationId.Geohash -> "geo:${id.channel.level.name}:${id.channel.geohash}"
    }

    fun decode(token: String): ConversationId? = when {
        token == "mesh" -> ConversationId.PublicMesh
        token.startsWith("ch:") -> ConversationId.Channel(token.removePrefix("ch:"))
        token.startsWith("pm:") -> ConversationId.Private(PeerId(token.removePrefix("pm:")))
        token.startsWith("geo:") -> {
            val parts = token.removePrefix("geo:").split(":", limit = 2)
            val level = parts.getOrNull(0)?.let { runCatching { GeohashLevel.valueOf(it) }.getOrNull() }
            val geohash = parts.getOrNull(1)
            if (level != null && geohash != null) ConversationId.Geohash(GeohashChannel(level, geohash)) else null
        }
        else -> null
    }

    fun decodeSet(raw: String): Set<ConversationId> =
        raw.split(SEPARATOR).mapNotNull { decode(it) }.toSet()

    fun encodeSet(ids: Set<ConversationId>): String = ids.joinToString(SEPARATOR) { encode(it) }
}
