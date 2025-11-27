package com.bitchat.android.onboarding

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import jakarta.inject.Singleton

/**
 * Manages Battery Optimization settings.
 * Exposes a reactive StateFlow<BatteryOptimizationStatus>.
 */
@Singleton
class BatteryOptimizationManager(
    private val application: Application
) {

    companion object {
        private const val TAG = "BatteryOptimizationManager"
    }

    private val _status = MutableStateFlow(BatteryOptimizationStatus.NOT_SUPPORTED)
    val status: StateFlow<BatteryOptimizationStatus> = _status.asStateFlow()

    private var batteryOptimizationLauncher: ActivityResultLauncher<Intent>? = null

    init {
        checkBatteryOptimizationStatus()
    }

    fun setLauncher(launcher: ActivityResultLauncher<Intent>) {
        this.batteryOptimizationLauncher = launcher
    }

    fun isBatteryOptimizationDisabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = application.getSystemService(Context.POWER_SERVICE) as PowerManager
                powerManager.isIgnoringBatteryOptimizations(application.packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Error checking battery optimization status", e)
                false
            }
        } else {
            true // Battery optimization doesn't exist on Android < 6.0
        }
    }

    fun isBatteryOptimizationSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }

    fun checkBatteryOptimizationStatus(): BatteryOptimizationStatus {
        val newStatus = when {
            !isBatteryOptimizationSupported() -> BatteryOptimizationStatus.NOT_SUPPORTED
            isBatteryOptimizationDisabled() -> BatteryOptimizationStatus.DISABLED
            else -> BatteryOptimizationStatus.ENABLED
        }
        _status.value = newStatus
        return newStatus
    }

    fun requestDisableBatteryOptimization() {
        Log.d(TAG, "Requesting user to disable battery optimization")
        
        if (!isBatteryOptimizationSupported()) {
            Log.d(TAG, "Battery optimization not supported on this device")
            _status.value = BatteryOptimizationStatus.NOT_SUPPORTED
            return
        }
        
        if (isBatteryOptimizationDisabled()) {
            Log.d(TAG, "Battery optimization already disabled")
            _status.value = BatteryOptimizationStatus.DISABLED
            return
        }
        
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${application.packageName}")
            }
            batteryOptimizationLauncher?.launch(intent)
            Log.d(TAG, "Launched battery optimization settings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch battery optimization settings", e)
            _status.value = BatteryOptimizationStatus.ENABLED
        }
    }

    /**
     * Open general battery optimization settings if direct request fails
     */
    private fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent().apply {
                action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            }
            
            if (intent.resolveActivity(application.packageManager) != null) {
                batteryOptimizationLauncher?.launch(intent)
            } else {
                // Fallback to general application settings
                openAppSettings()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening battery optimization settings", e)
        }
    }

    /**
     * Open app settings as a last resort
     */
    private fun openAppSettings() {
        try {
            val intent = Intent().apply {
                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                data = Uri.fromParts("package", application.packageName, null)
            }
            batteryOptimizationLauncher?.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app settings", e)
        }
    }

    /**
     * Log battery optimization status for debugging
     */
    fun logBatteryOptimizationStatus() {
        Log.d(TAG, "Battery optimization status: ${status.value}")
    }
}

enum class BatteryOptimizationStatus {
    ENABLED,
    DISABLED,
    NOT_SUPPORTED
}