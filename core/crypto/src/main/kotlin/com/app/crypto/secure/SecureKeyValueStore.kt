package com.app.crypto.secure

/**
 * Encrypted key-value storage for secrets at rest (identity keys, fingerprints,
 * signing keys). Values are encrypted before they ever touch disk.
 *
 * Replaces the deprecated `androidx.security:security-crypto`
 * (`EncryptedSharedPreferences`). The Android implementation is backed by Google
 * Tink (see [TinkSecureKeyValueStore]); the interface itself carries no Android
 * types so it can move to `commonMain` unchanged when the project goes KMP.
 */
interface SecureKeyValueStore {

    fun getString(key: String): String?

    fun putString(key: String, value: String)

    fun getStringSet(key: String): Set<String>?

    fun putStringSet(key: String, values: Set<String>)

    fun contains(key: String): Boolean

    fun remove(vararg keys: String)

    fun clear()
}
