package com.app.data.repository

import com.app.common.settings.SettingsStore
import com.app.domain.model.ConversationId
import com.app.domain.model.GeohashChannel
import com.app.domain.model.GeohashLevel
import com.app.domain.model.PeerId
import com.app.domain.repository.ConversationPrefsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Pin/mute flags over the shared [SettingsStore]. Each set is a newline-joined list of stable
 * [ConversationId] keys (ids never contain a newline), read reactively via the store's flow.
 */
@SingleIn(AppScope::class)
@Inject
internal class ConversationPrefsRepositoryImpl(
    private val settings: SettingsStore,
) : ConversationPrefsRepository {

    override fun observePinned(): Flow<Set<ConversationId>> = observeSet(KEY_PINNED)

    override fun observeMuted(): Flow<Set<ConversationId>> = observeSet(KEY_MUTED)

    override suspend fun setPinned(id: ConversationId, pinned: Boolean) = setFlag(KEY_PINNED, id, pinned)

    override suspend fun setMuted(id: ConversationId, muted: Boolean) = setFlag(KEY_MUTED, id, muted)

    private fun observeSet(key: String): Flow<Set<ConversationId>> =
        settings.getStringFlow(key, "").map(::decodeSet)

    private fun setFlag(key: String, id: ConversationId, on: Boolean) {
        val current = decodeSet(settings.getString(key, "")).toMutableSet()
        if (on) current.add(id) else current.remove(id)
        settings.putString(key, current.joinToString(SEPARATOR) { encode(it) })
    }

    private fun decodeSet(raw: String): Set<ConversationId> =
        raw.split(SEPARATOR).mapNotNull { decode(it) }.toSet()

    private fun encode(id: ConversationId): String = when (id) {
        ConversationId.PublicMesh -> "mesh"
        is ConversationId.Channel -> "ch:${id.tag}"
        is ConversationId.Private -> "pm:${id.peer.raw}"
        is ConversationId.Geohash -> "geo:${id.channel.level.name}:${id.channel.geohash}"
    }

    private fun decode(token: String): ConversationId? = when {
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

    private companion object {
        const val KEY_PINNED = "pinned_conversations"
        const val KEY_MUTED = "muted_conversations"
        const val SEPARATOR = "\n"
    }
}
