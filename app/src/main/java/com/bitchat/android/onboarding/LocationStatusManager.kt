package com.bitchat.android.onboarding

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import jakarta.inject.Singleton

/**
 * Manages Location Services enable/disable state.
 * Exposes a reactive StateFlow<LocationStatus>.
 */
@Singleton
class LocationStatusManager(
    private val application: Application
) {

    companion object {
        private const val TAG = "LocationStatusManager"
    }

    private val _status = MutableStateFlow(LocationStatus.NOT_AVAILABLE)
    val status: StateFlow<LocationStatus> = _status.asStateFlow()

    private var locationManager: LocationManager? = null
    private var locationSettingsLauncher: ActivityResultLauncher<Intent>? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == LocationManager.MODE_CHANGED_ACTION || 
                intent.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                Log.d(TAG, "Location settings changed, checking status")
                checkLocationStatus()
            }
        }
    }

    init {
        setupLocationManager()
        // Initial check
        checkLocationStatus()
        // Register receiver for location state changes
        val filter = IntentFilter().apply {
            addAction(LocationManager.MODE_CHANGED_ACTION)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(application, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun setLauncher(launcher: ActivityResultLauncher<Intent>) {
        this.locationSettingsLauncher = launcher
    }

    /**
     * Setup LocationManager reference
     */
    private fun setupLocationManager() {
        try {
            locationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            Log.d(TAG, "LocationManager initialized: ${locationManager != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize LocationManager", e)
            locationManager = null
        }
    }

    fun isLocationEnabled(): Boolean {
        return try {
            locationManager?.let { lm ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // API 28+ (Android 9) - Modern approach
                    lm.isLocationEnabled
                } else {
                    // Older devices - Check individual providers
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                }
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking location enabled state: ${e.message}")
            false
        }
    }

    /**
     * Check location services status
     * This should be called on every app startup
     */
    fun checkLocationStatus(): LocationStatus {
        val newStatus = when {
            locationManager == null -> {
                Log.e(TAG, "LocationManager not available on this device")
                LocationStatus.NOT_AVAILABLE
            }
            !isLocationEnabled() -> {
                Log.w(TAG, "Location services are disabled")
                LocationStatus.DISABLED
            }
            else -> {
                Log.d(TAG, "Location services are enabled and ready")
                LocationStatus.ENABLED
            }
        }
        _status.value = newStatus
        return newStatus
    }

    /**
     * Request user to enable location services
     * Opens system location settings screen
     */
    fun requestEnableLocation() {
        Log.d(TAG, "Requesting user to enable location services")
        
        try {
            val enableLocationIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            locationSettingsLauncher?.launch(enableLocationIntent) ?: run {
                Log.e(TAG, "Location launcher not set")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request location enable", e)
        }
    }

    fun getDiagnostics(): String {
        return buildString {
            appendLine("Location Services Status Diagnostics:")
            appendLine("LocationManager available: ${locationManager != null}")
            appendLine("Location services enabled: ${isLocationEnabled()}")
            appendLine("Current status: ${status.value}")
            appendLine("Android version: ${Build.VERSION.SDK_INT}")
            
            locationManager?.let { lm ->
                try {
                    appendLine("GPS provider enabled: ${lm.isProviderEnabled(LocationManager.GPS_PROVIDER)}")
                    appendLine("Network provider enabled: ${lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)}")
                } catch (e: Exception) {
                    appendLine("Provider details: [Error: ${e.message}]")
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    appendLine("Using modern isLocationEnabled() API")
                } else {
                    appendLine("Using legacy provider check API")
                }
            }
        }
    }

    /**
     * Log current location status for debugging
     */
    fun logLocationStatus() {
        Log.d(TAG, getDiagnostics())
    }

    /**
     * Cleanup resources - call this when activity is destroyed
     */
    fun cleanup() {
        try {
            application.unregisterReceiver(receiver)
            Log.d(TAG, "BroadcastReceiver unregistered")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver was not registered")
        }
    }
}

/**
 * Location services status enum
 */
enum class LocationStatus {
    ENABLED,
    DISABLED, 
    NOT_AVAILABLE
}
