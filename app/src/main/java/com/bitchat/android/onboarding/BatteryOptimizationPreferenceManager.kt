package com.bitchat.android.onboarding

import android.content.Context
import com.bitchat.android.core.data.appSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Preference manager for battery optimization skip choice
 */
object BatteryOptimizationPreferenceManager {
    private const val KEY_BATTERY_SKIP = "battery_optimization_skipped"

    private val _skipFlow = MutableStateFlow(false)
    val skipFlow: StateFlow<Boolean> = _skipFlow

    fun init(context: Context) {
        _skipFlow.value = isSkipped(context)
    }

    fun setSkipped(context: Context, skipped: Boolean) {
        appSettings(context).putBoolean(KEY_BATTERY_SKIP, skipped)
        _skipFlow.value = skipped
    }

    fun isSkipped(context: Context): Boolean {
        return appSettings(context).getBoolean(KEY_BATTERY_SKIP, false)
    }
}
