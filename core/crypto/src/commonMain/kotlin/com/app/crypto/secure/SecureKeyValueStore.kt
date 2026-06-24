package com.app.crypto.secure

/**
 * Encrypted key-value storage for secrets at rest (identity keys, fingerprints,
 * signing keys). Values are encrypted before they ever touch disk.
 *
 * The Android implementation is backed by KSafe (AES-256-GCM, AES key in the Android
 * Keystore/TEE); the interface itself carries no platform types and lives in `commonMain`,
 * so each platform's DI graph provides its own backing store.
 */
interface SecureKeyValueStore {

    fun getString(key: String): String?

    fun putString(key: String, value: String)

    fun getStringSet(key: String): Set<String>?

    fun putStringSet(key: String, values: Set<String>)

    fun contains(key: String): Boolean

    fun remove(vararg keys: String)

    suspend fun clear()
}
