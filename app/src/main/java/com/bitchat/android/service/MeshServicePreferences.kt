package com.bitchat.android.service

import com.russhwolf.settings.ObservableSettings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Graph-owned foreground-service preferences (formerly an `object` with an init(context)
 * + lateinit settings — a crash window when read before init).
 */
@SingleIn(AppScope::class)
@Inject
class MeshServicePreferences(private val settings: ObservableSettings) {

    private companion object {
        const val KEY_AUTO_START = "auto_start_on_boot"
        const val KEY_BACKGROUND_ENABLED = "background_enabled"
    }

    fun isAutoStartEnabled(default: Boolean = true): Boolean =
        settings.getBoolean(KEY_AUTO_START, default)

    fun setAutoStartEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_AUTO_START, enabled)
    }

    fun isBackgroundEnabled(default: Boolean = true): Boolean =
        settings.getBoolean(KEY_BACKGROUND_ENABLED, default)

    fun setBackgroundEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_BACKGROUND_ENABLED, enabled)
    }
}
