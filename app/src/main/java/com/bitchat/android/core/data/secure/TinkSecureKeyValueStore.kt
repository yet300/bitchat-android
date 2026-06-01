package com.bitchat.android.core.data.secure

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import com.bitchat.android.serialization.JsonConfig
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Tink-backed [SecureKeyValueStore].
 *
 * Each value is sealed with an AES-256-GCM AEAD whose keyset is itself wrapped by
 * a master key held in the Android Keystore (`android-keystore://`). The sealed
 * ciphertext is Base64-encoded and kept in a plain [SharedPreferences] file named
 * [storeName] — the file never holds plaintext.
 *
 * The entry key is bound in as associated data, so a ciphertext copied from one
 * key to another fails to decrypt.
 *
 * The keyset is process-global (one master key for the app), so it is built once
 * and shared across every store instance regardless of [storeName].
 */
internal class TinkSecureKeyValueStore(
    context: Context,
    storeName: String,
) : SecureKeyValueStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(storeName, Context.MODE_PRIVATE)

    private val aead: Aead = aead(context.applicationContext)

    override fun getString(key: String): String? {
        val encoded = prefs.getString(key, null) ?: return null
        return decrypt(key, encoded)
    }

    override fun putString(key: String, value: String) {
        prefs.edit { putString(key, encrypt(key, value)) }
    }

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

    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun remove(vararg keys: String) {
        prefs.edit { keys.forEach(::remove) }
    }

    override fun clear() {
        prefs.edit { clear() }
    }

    private fun encrypt(key: String, value: String): String {
        val aad = key.toByteArray(Charsets.UTF_8)
        val ciphertext = aead.encrypt(value.toByteArray(Charsets.UTF_8), aad)
        return Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(key: String, encoded: String): String? = runCatching {
        val aad = key.toByteArray(Charsets.UTF_8)
        val ciphertext = Base64.decode(encoded, Base64.NO_WRAP)
        String(aead.decrypt(ciphertext, aad), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        private const val KEYSET_NAME = "bitchat_tink_keyset"
        private const val KEYSET_PREFS = "bitchat_tink_keyset_prefs"
        private const val MASTER_KEY_URI = "android-keystore://bitchat_tink_master_key"

        @Volatile
        private var cachedAead: Aead? = null

        fun aead(context: Context): Aead {
            cachedAead?.let { return it }
            return synchronized(this) {
                cachedAead ?: run {
                    AeadConfig.register()
                    val handle = AndroidKeysetManager.Builder()
                        .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
                        .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                        .withMasterKeyUri(MASTER_KEY_URI)
                        .build()
                        .keysetHandle
                    handle.getPrimitive(Aead::class.java).also { cachedAead = it }
                }
            }
        }
    }
}
