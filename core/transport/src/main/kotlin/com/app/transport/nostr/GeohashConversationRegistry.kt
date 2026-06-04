package com.app.transport.nostr

import android.content.Context
import com.app.common.appSettings
import com.app.common.serialization.JsonConfig
import com.russhwolf.settings.Settings
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.util.concurrent.ConcurrentHashMap

/**
 * GeohashConversationRegistry
 * - Global, thread-safe registry of conversationKey (e.g., "nostr_<pub16>") -> source geohash
 * - Enables routing geohash DMs from anywhere by providing the correct geohash identity
 * - Persisted as a single JSON map under [KEY] in the shared app settings store.
 */
object GeohashConversationRegistry {
    private val map = ConcurrentHashMap<String, String>()
    private const val KEY = "geohash_conversation_registry"
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private var settings: Settings? = null

    fun initialize(context: Context) {
        if (settings == null) {
            settings = appSettings(context)
            load()
        }
    }

    private fun load() {
        val json = settings?.getStringOrNull(KEY) ?: return
        runCatching { JsonConfig.json.decodeFromString(serializer, json) }
            .getOrNull()?.let { map.putAll(it) }
    }

    private fun persist() {
        settings?.putString(KEY, JsonConfig.json.encodeToString(serializer, HashMap(map)))
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
        settings?.remove(KEY)
    }
}
