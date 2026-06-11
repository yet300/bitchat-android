@file:OptIn(ExperimentalSettingsApi::class)

package com.app.data.repository

import com.app.common.settings.SettingsStore
import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.coroutines.getStringFlow
import com.russhwolf.settings.coroutines.getStringOrNullFlow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow

/**
 * [SettingsStore] over the shared [ObservableSettings] (multiplatform-settings). The settings
 * infrastructure library is confined to this implementation; callers depend on the domain port.
 */
@SingleIn(AppScope::class)
@Inject
internal class SettingsStoreImpl(
    private val settings: ObservableSettings,
) : SettingsStore {

    override fun getString(key: String, defaultValue: String): String = settings.getString(key, defaultValue)

    override fun getStringOrNull(key: String): String? = settings.getStringOrNull(key)

    override fun putString(key: String, value: String) = settings.putString(key, value)

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = settings.getBoolean(key, defaultValue)

    override fun putBoolean(key: String, value: Boolean) = settings.putBoolean(key, value)

    override fun getInt(key: String, defaultValue: Int): Int = settings.getInt(key, defaultValue)

    override fun putInt(key: String, value: Int) = settings.putInt(key, value)

    override fun getLong(key: String, defaultValue: Long): Long = settings.getLong(key, defaultValue)

    override fun putLong(key: String, value: Long) = settings.putLong(key, value)

    override fun getDouble(key: String, defaultValue: Double): Double = settings.getDouble(key, defaultValue)

    override fun putDouble(key: String, value: Double) = settings.putDouble(key, value)

    override fun hasKey(key: String): Boolean = settings.hasKey(key)

    override fun remove(key: String) = settings.remove(key)

    override fun getStringFlow(key: String, defaultValue: String): Flow<String> =
        settings.getStringFlow(key, defaultValue)

    override fun getStringOrNullFlow(key: String): Flow<String?> =
        settings.getStringOrNullFlow(key)
}
