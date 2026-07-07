package com.app.transport.nostr

import com.app.common.serialization.JsonConfig
import co.touchlab.stately.collections.ConcurrentMutableMap
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * GeohashConversationRegistry
 * - Global, thread-safe registry of conversationKey (e.g., "nostr_<pub16>") -> source geohash
 * - Enables routing geohash DMs from anywhere by providing the correct geohash identity
 * - Persisted as a single JSON map through the [NostrConfigStore] port.
 *
 * App-scoped singleton: the in-memory map is loaded once on construction and persisted on writes.
 */
class GeohashConversationRegistry(
    private val store: NostrConfigStore,
) {
    private val map = ConcurrentMutableMap<String, String>()
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    init {
        load()
    }

    private fun load() {
        val json = store.getConversationRegistryJson() ?: return
        runCatching { JsonConfig.json.decodeFromString(serializer, json) }
            .getOrNull()?.let { map.putAll(it) }
    }

    private fun persist() {
        store.setConversationRegistryJson(JsonConfig.json.encodeToString(serializer, HashMap(map)))
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
        store.clearConversationRegistryJson()
    }
}
