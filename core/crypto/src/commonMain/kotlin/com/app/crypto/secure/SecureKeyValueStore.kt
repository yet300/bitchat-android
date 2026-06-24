package com.app.crypto.secure

/**
 * Encrypted key-value storage for secrets at rest (identity keys, fingerprints,
 * signing keys). Values are encrypted before they ever touch disk.
 *
 * The Android implementation is backed by KSafe (AES-256-GCM, AES key in the Android
 * Keystore/TEE); the interface itself carries no Android types so it can move to
 * `commonMain` unchanged when the project goes KMP.
 */
internal interface SecureKeyValueStore {

    fun getString(key: String): String?

    fun putString(key: String, value: String)

    fun getStringSet(key: String): Set<String>?

    fun putStringSet(key: String, values: Set<String>)

    fun contains(key: String): Boolean

    fun remove(vararg keys: String)

    suspend fun clear()
}
