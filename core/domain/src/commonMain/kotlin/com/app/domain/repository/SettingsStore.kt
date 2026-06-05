package com.app.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Low-level key/value settings port for non-secret preferences. Abstracts the concrete settings
 * backend (multiplatform-settings) away from the layers that read/write arbitrary keys, so neither
 * the domain nor the lower modules depend on the settings infrastructure library — only on this
 * interface. The implementation lives in :core:data.
 *
 * High-level, semantic settings (nickname, location-enabled, …) belong on [SettingsRepository];
 * this port is for components that persist their own ad-hoc keys (transport prefs, registries, etc).
 *
 * Secrets at rest go through the crypto layer's secure store, not here.
 */
interface SettingsStore {

    fun getString(key: String, defaultValue: String): String

    fun getStringOrNull(key: String): String?

    fun putString(key: String, value: String)

    fun getBoolean(key: String, defaultValue: Boolean): Boolean

    fun putBoolean(key: String, value: Boolean)

    fun getInt(key: String, defaultValue: Int): Int

    fun putInt(key: String, value: Int)

    fun getLong(key: String, defaultValue: Long): Long

    fun putLong(key: String, value: Long)

    fun getDouble(key: String, defaultValue: Double): Double

    fun putDouble(key: String, value: Double)

    fun hasKey(key: String): Boolean

    fun remove(key: String)

    /** Emits the current value for [key] and re-emits on every change. */
    fun getStringFlow(key: String, defaultValue: String): Flow<String>

    /** Emits the current value (or null when absent) for [key] and re-emits on every change. */
    fun getStringOrNullFlow(key: String): Flow<String?>
}
