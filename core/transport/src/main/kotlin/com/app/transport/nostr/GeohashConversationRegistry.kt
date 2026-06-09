package com.app.transport.nostr

import com.app.common.serialization.JsonConfig
import com.app.domain.repository.SettingsStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.util.concurrent.ConcurrentHashMap

/**
 * GeohashConversationRegistry
 * - Global, thread-safe registry of conversationKey (e.g., "nostr_<pub16>") -> source geohash
 * - Enables routing geohash DMs from anywhere by providing the correct geohash identity
 * - Persisted as a single JSON map under [KEY] through the [SettingsStore] port.
 *
 * App-scoped singleton: the in-memory map is loaded once on construction and persisted on writes.
 */
@SingleIn(AppScope::class)
@Inject
class GeohashConversationRegistry(
    private val settings: SettingsStore,
) {
    private val map = ConcurrentHashMap<String, String>()
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    init {
        load()
    }

    private fun load() {
        val json = settings.getStringOrNull(KEY) ?: return
        runCatching { JsonConfig.json.decodeFromString(serializer, json) }
            .getOrNull()?.let { map.putAll(it) }
    }

    private fun persist() {
        settings.putString(KEY, JsonConfig.json.encodeToString(serializer, HashMap(map)))
    }

    fun set(convKey: String, geohash: String) {
        if (geohash.isNotEmpty()) {
            map[convKey] = geohash
            persist()
        }
    }

    fun get(convKey: String): String? = map[convKey]

    fun snapshot(): Map<String, String> = map.toMap()

    fun clear() {
        map.clear()
        settings.remove(KEY)
    }

    private companion object {
        const val KEY = "geohash_conversation_registry"
    }
}
