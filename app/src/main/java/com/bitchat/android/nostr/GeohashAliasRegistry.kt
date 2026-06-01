package com.bitchat.android.nostr

import android.content.Context
import com.bitchat.android.core.data.appSettings
import com.russhwolf.settings.Settings
import java.util.concurrent.ConcurrentHashMap

/**
 * GeohashAliasRegistry
 * - Global, thread-safe registry for alias->Nostr pubkey mappings (e.g., nostr_<pub16> -> pubkeyHex)
 * - Persisted via multiplatform-settings to survive app restarts.
 */
object GeohashAliasRegistry {
    private val map: MutableMap<String, String> = ConcurrentHashMap()
    private const val PREFS_NAME = "geohash_alias_registry"
    private var settings: Settings? = null

    fun initialize(context: Context) {
        if (settings == null) {
            settings = appSettings(context, PREFS_NAME)
            loadFromSettings()
        }
    }

    private fun loadFromSettings() {
        settings?.let { s ->
            for (key in s.keys) {
                s.getStringOrNull(key)?.let { map[key] = it }
            }
        }
    }

    fun put(alias: String, pubkeyHex: String) {
        map[alias] = pubkeyHex
        settings?.putString(alias, pubkeyHex)
    }

    fun get(alias: String): String? = map[alias]

    fun contains(alias: String): Boolean = map.containsKey(alias)

    fun snapshot(): Map<String, String> = HashMap(map)

    fun clear() {
        map.clear()
        settings?.clear()
    }
}
