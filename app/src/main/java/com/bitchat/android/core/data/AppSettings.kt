package com.bitchat.android.core.data

import android.content.Context
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.SharedPreferencesSettings

/**
 * Builds an [ObservableSettings] backed by a named `SharedPreferences` file.
 *
 * Thin platform factory replacing scattered `context.getSharedPreferences(...)`
 * calls with the multiplatform-settings abstraction. Consumers depend only on
 * the `Settings` API, so the non-secure preference code is ready to move to
 * `commonMain` (with a platform-provided factory) on the KMP step — at the DI
 * step this becomes a Metro provider.
 *
 * For secrets at rest use [com.bitchat.android.core.data.secure.SecureKeyValueStore]
 * instead — this store is not encrypted.
 */
fun appSettings(context: Context, name: String): ObservableSettings =
    SharedPreferencesSettings(context.getSharedPreferences(name, Context.MODE_PRIVATE))
