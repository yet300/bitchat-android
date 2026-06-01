package com.bitchat.android.service

import android.content.Context
import com.bitchat.android.core.data.appSettings
import com.russhwolf.settings.Settings

object MeshServicePreferences {
    private const val KEY_AUTO_START = "auto_start_on_boot"
    private const val KEY_BACKGROUND_ENABLED = "background_enabled"

    private lateinit var settings: Settings

    fun init(context: Context) {
        settings = appSettings(context)
    }

    fun isAutoStartEnabled(default: Boolean = true): Boolean {
        return settings.getBoolean(KEY_AUTO_START, default)
    }

    fun setAutoStartEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_AUTO_START, enabled)
    }

    fun isBackgroundEnabled(default: Boolean = true): Boolean {
        return settings.getBoolean(KEY_BACKGROUND_ENABLED, default)
    }

    fun setBackgroundEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_BACKGROUND_ENABLED, enabled)
    }
}
