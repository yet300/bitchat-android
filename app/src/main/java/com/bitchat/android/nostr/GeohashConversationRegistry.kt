package com.bitchat.android.nostr

import android.content.Context
import com.bitchat.android.core.data.appSettings
import com.russhwolf.settings.Settings
import java.util.concurrent.ConcurrentHashMap

/**
 * GeohashConversationRegistry
 * - Global, thread-safe registry of conversationKey (e.g., "nostr_<pub16>") -> source geohash
 * - Enables routing geohash DMs from anywhere by providing the correct geohash identity
 * - Persisted via multiplatform-settings to survive app restarts.
 */
object GeohashConversationRegistry {
    private val map = ConcurrentHashMap<String, String>()
    private const val PREFS_NAME = "geohash_conversation_registry"
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

    fun set(convKey: String, geohash: String) {
        if (geohash.isNotEmpty()) {
            map[convKey] = geohash
            settings?.putString(convKey, geohash)
        }
    }

    fun get(convKey: String): String? = map[convKey]

    fun snapshot(): Map<String, String> = map.toMap()

    fun clear() {
        map.clear()
        settings?.clear()
    }
}
