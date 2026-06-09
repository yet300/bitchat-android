package com.app.data

import android.content.Context
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.SharedPreferencesSettings

/**
 * Single app-wide [ObservableSettings] for all non-secret preferences
 * (BlockBlast style: one store, namespaced keys, never enumerate keys).
 *
 * Backed by one `SharedPreferences` file shared across the whole app. The
 * instance is built once via a [lazy] delegate; assigning the (application)
 * context on each call is idempotent. At the Metro DI step this collapses into
 * a `@Provides @SingleIn(AppScope) fun provideSettings(): Settings = Settings()`
 * singleton — call sites already depend only on the `Settings` API.
 *
 * Secrets at rest (identity / signing keys, fingerprints) go through
 * [com.app.crypto.secure.SecureKeyValueStore] (Tink), not here —
 * this store is not encrypted.
 */
fun appSettings(context: Context): ObservableSettings = AppSettings.get(context)

private object AppSettings {
    private const val STORE_FILE = "bitchat"

    private lateinit var appContext: Context

    private val instance: ObservableSettings by lazy {
        SharedPreferencesSettings(appContext.getSharedPreferences(STORE_FILE, Context.MODE_PRIVATE))
    }

    fun get(context: Context): ObservableSettings {
        appContext = context.applicationContext
        return instance
    }
}
