package com.bitchat.android.nostr

import android.content.Context
import com.bitchat.android.core.data.appSettings
import com.app.common.serialization.JsonConfig
import com.russhwolf.settings.Settings
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.util.concurrent.ConcurrentHashMap

/**
 * GeohashAliasRegistry
 * - Global, thread-safe registry for alias->Nostr pubkey mappings (e.g., nostr_<pub16> -> pubkeyHex)
 * - Persisted as a single JSON map under [KEY] in the shared app settings store.
 */
object GeohashAliasRegistry {
    private val map: MutableMap<String, String> = ConcurrentHashMap()
    private const val KEY = "geohash_alias_registry"
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

    fun put(alias: String, pubkeyHex: String) {
        map[alias] = pubkeyHex
        persist()
    }

    fun get(alias: String): String? = map[alias]

    fun contains(alias: String): Boolean = map.containsKey(alias)

    fun snapshot(): Map<String, String> = HashMap(map)

    fun clear() {
        map.clear()
        settings?.remove(KEY)
    }
}
