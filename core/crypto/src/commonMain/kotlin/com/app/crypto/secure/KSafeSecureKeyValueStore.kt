package com.app.crypto.secure

import com.app.common.serialization.JsonConfig
import eu.anifantakis.lib.ksafe.KSafe
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

/**
 * [SecureKeyValueStore] backed by a KSafe encrypted instance (AES-256-GCM, AES key in the Android
 * Keystore / TEE). Every value written here is encrypted at rest — KSafe is encrypted by default, so
 * no per-call mode is passed. Replaces the Tink-backed store; the entry key is the storage key.
 *
 * Synchronous reads/writes go through KSafe's `*Direct` API (hot-cache reads, coalesced background
 * writes), preserving the synchronous contract the callers rely on. String sets are JSON-encoded
 * through the string path, exactly as the previous Tink store did, so on-disk shape is unchanged.
 */
class KSafeSecureKeyValueStore(private val ksafe: KSafe) : SecureKeyValueStore {

    override fun getString(key: String): String? =
        if (ksafe.getKeyInfo(key) == null) null else ksafe.getDirect(key, "")

    override fun putString(key: String, value: String) = ksafe.putDirect(key, value)

    override fun getStringSet(key: String): Set<String>? {
        val json = getString(key) ?: return null
        return runCatching {
            JsonConfig.json.decodeFromString(SetSerializer(String.serializer()), json)
        }.getOrNull()
    }

    override fun putStringSet(key: String, values: Set<String>) {
        val json = JsonConfig.json.encodeToString(SetSerializer(String.serializer()), values)
        putString(key, json)
    }

    override fun contains(key: String): Boolean = ksafe.getKeyInfo(key) != null

    override fun remove(vararg keys: String) {
        keys.forEach(ksafe::deleteDirect)
    }

    override suspend fun clear() = ksafe.clearAll()
}
