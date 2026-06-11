package com.bitchat.android.onboarding

import com.russhwolf.settings.ObservableSettings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Graph-owned preference for the battery-optimization skip choice
 * (formerly an `object` calling the global appSettings()).
 */
@SingleIn(AppScope::class)
@Inject
class BatteryOptimizationPreferenceManager(private val settings: ObservableSettings) {

    private companion object {
        const val KEY_BATTERY_SKIP = "battery_optimization_skipped"
    }

    fun setSkipped(skipped: Boolean) {
        settings.putBoolean(KEY_BATTERY_SKIP, skipped)
    }

    fun isSkipped(): Boolean = settings.getBoolean(KEY_BATTERY_SKIP, false)
}
