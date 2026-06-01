package com.bitchat.android.onboarding

import android.content.Context
import com.bitchat.android.core.data.appSettings

/**
 * Preference manager for background location skip choice.
 */
object BackgroundLocationPreferenceManager {
    private const val KEY_BACKGROUND_LOCATION_SKIP = "background_location_skipped"

    fun setSkipped(context: Context, skipped: Boolean) {
        appSettings(context).putBoolean(KEY_BACKGROUND_LOCATION_SKIP, skipped)
    }

    fun isSkipped(context: Context): Boolean {
        return appSettings(context).getBoolean(KEY_BACKGROUND_LOCATION_SKIP, false)
    }
}
