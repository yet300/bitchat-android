package com.app.crypto.secure

import com.app.common.serialization.JsonConfig
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

/**
 * In-memory [SecureKeyValueStore] for host (Robolectric) tests. KSafe's encrypted mode needs a real
 * Android Keystore and fails closed without one, so host tests install this through
 * [SecureStores.factory] instead. Mirrors the production storage shape (sets JSON-encoded through the
 * string path) and shares one instance across all callers in a test, matching the single-file
 * semantics of the real store.
 */
internal class InMemorySecureKeyValueStore : SecureKeyValueStore {

    private val map = LinkedHashMap<String, String>()

    @Synchronized override fun getString(key: String): String? = map[key]

    @Synchronized override fun putString(key: String, value: String) {
        map[key] = value
    }

    override fun getStringSet(key: String): Set<String>? {
        val json = getString(key) ?: return null
        return runCatching {
            JsonConfig.json.decodeFromString(SetSerializer(String.serializer()), json)
        }.getOrNull()
    }

    override fun putStringSet(key: String, values: Set<String>) {
        putString(key, JsonConfig.json.encodeToString(SetSerializer(String.serializer()), values))
    }

    @Synchronized override fun contains(key: String): Boolean = map.containsKey(key)

    @Synchronized override fun remove(vararg keys: String) {
        keys.forEach(map::remove)
    }

    override suspend fun clear() = synchronized(map) { map.clear() }
}
