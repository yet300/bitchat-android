package com.bitchat.android.onboarding

import com.russhwolf.settings.ObservableSettings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Graph-owned preference for the background-location skip choice
 * (formerly an `object` calling the global appSettings()).
 */
@SingleIn(AppScope::class)
@Inject
class BackgroundLocationPreferenceManager(private val settings: ObservableSettings) {

    private companion object {
        const val KEY_BACKGROUND_LOCATION_SKIP = "background_location_skipped"
    }

    fun setSkipped(skipped: Boolean) {
        settings.putBoolean(KEY_BACKGROUND_LOCATION_SKIP, skipped)
    }

    fun isSkipped(): Boolean = settings.getBoolean(KEY_BACKGROUND_LOCATION_SKIP, false)
}
