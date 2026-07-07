package com.app.transport.nostr

import com.app.common.serialization.JsonConfig
import co.touchlab.stately.collections.ConcurrentMutableMap
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * GeohashAliasRegistry
 * - Global, thread-safe registry for alias->Nostr pubkey mappings (e.g., nostr_<pub16> -> pubkeyHex)
 * - Persisted as a single JSON map through the [NostrConfigStore] port.
 *
 * App-scoped singleton: the in-memory map is loaded once on construction and persisted on writes.
 */
class GeohashAliasRegistry(
    private val store: NostrConfigStore,
) {
    private val map: MutableMap<String, String> = ConcurrentMutableMap()
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    init {
        load()
    }

    private fun load() {
        val json = store.getAliasRegistryJson() ?: return
        runCatching { JsonConfig.json.decodeFromString(serializer, json) }
            .getOrNull()?.let { map.putAll(it) }
    }

    private fun persist() {
        store.setAliasRegistryJson(JsonConfig.json.encodeToString(serializer, HashMap(map)))
    }

    fun put(alias: String, pubkeyHex: String) {
        map[alias] = pubkeyHex
        persist()
    }

    fun get(alias: String): String? = map[alias]

    fun contains(alias: String): Boolean = map.containsKey(alias)

    fun snapshot(): Map<String, String> = HashMap(map)

    fun clear() {
        map.clear()
        store.clearAliasRegistryJson()
    }
}
