package com.bitchat.android.onboarding

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import jakarta.inject.Singleton

/**
 * Manages Bluetooth enable/disable state.
 * Exposes a reactive StateFlow<BluetoothStatus>.
 */
@Singleton
class BluetoothStatusManager(
    private val application: Application
) {

    companion object {
        private const val TAG = "BluetoothStatusManager"
    }

    private val _status = MutableStateFlow(BluetoothStatus.NOT_SUPPORTED)
    val status: StateFlow<BluetoothStatus> = _status.asStateFlow()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothEnableLauncher: ActivityResultLauncher<Intent>? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    Log.d(TAG, "Bluetooth state changed: ${getAdapterStateName(state)}")
                    checkBluetoothStatus()
                }
            }
        }
    }

    init {
        setupBluetoothAdapter()
        // Initial check
        checkBluetoothStatus()
        // Register receiver
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(application, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun setLauncher(launcher: ActivityResultLauncher<Intent>) {
        this.bluetoothEnableLauncher = launcher
    }

    /**
     * Setup Bluetooth adapter reference
     */
    private fun setupBluetoothAdapter() {
        try {
            val bluetoothManager = application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager.adapter
            Log.d(TAG, "Bluetooth adapter initialized: ${bluetoothAdapter != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Bluetooth adapter", e)
            bluetoothAdapter = null
        }
    }

    /**
     * Check if Bluetooth is supported on this device
     */
    fun isBluetoothSupported(): Boolean {
        return bluetoothAdapter != null
    }

    /**
     * Check if Bluetooth is currently enabled (permission-safe)
     */
    fun isBluetoothEnabled(): Boolean {
        return try {
            bluetoothAdapter?.isEnabled == true
        } catch (securityException: SecurityException) {
            // If we can't check due to permissions, assume disabled
            Log.w(TAG, "Cannot check Bluetooth enabled state due to missing permissions")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking Bluetooth enabled state: ${e.message}")
            false
        }
    }

    /**
     * Check Bluetooth status and handle accordingly (permission-safe)
     * This should be called on every app startup
     */
    fun checkBluetoothStatus(): BluetoothStatus {
        val newStatus = when {
            bluetoothAdapter == null -> {
                Log.e(TAG, "Bluetooth not supported on this device")
                BluetoothStatus.NOT_SUPPORTED
            }
            !isBluetoothEnabled() -> {
                Log.w(TAG, "Bluetooth is disabled or cannot be checked")
                BluetoothStatus.DISABLED
            }
            else -> {
                // Log.d(TAG, "Bluetooth is enabled and ready")
                BluetoothStatus.ENABLED
            }
        }
        _status.value = newStatus
        return newStatus
    }

    /**
     * Request user to enable Bluetooth (permission-aware)
     */
    fun requestEnableBluetooth() {
        Log.d(TAG, "Requesting user to enable Bluetooth")
        
        try {
            val enableBluetoothIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            bluetoothEnableLauncher?.launch(enableBluetoothIntent) ?: run {
                Log.e(TAG, "Bluetooth launcher not set")
            }
        } catch (securityException: SecurityException) {
            Log.w(TAG, "Cannot request Bluetooth enable due to missing BLUETOOTH_CONNECT permission")
            // Status will remain DISABLED, UI should handle permission request
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Bluetooth enable", e)
        }
    }

    fun getDiagnostics(): String {
        return buildString {
            appendLine("Bluetooth Status Diagnostics:")
            appendLine("Adapter available: ${bluetoothAdapter != null}")
            appendLine("Bluetooth supported: ${isBluetoothSupported()}")
            appendLine("Bluetooth enabled: ${isBluetoothEnabled()}")
            appendLine("Current status: ${status.value}")
            
            // Only access adapter details if we have permission and adapter is available
            bluetoothAdapter?.let { adapter ->
                try {
                    // These calls require BLUETOOTH_CONNECT permission on Android 12+
                    appendLine("Adapter name: ${adapter.name ?: "Unknown"}")
                    appendLine("Adapter address: ${adapter.address ?: "Unknown"}")
                } catch (securityException: SecurityException) {
                    // Permission not granted yet, skip detailed info
                    appendLine("Adapter details: [Permission required]")
                } catch (e: Exception) {
                    appendLine("Adapter details: [Error: ${e.message}]")
                }
                appendLine("Adapter state: ${getAdapterStateName(adapter.state)}")
            }
        }
    }

    private fun getAdapterStateName(state: Int): String {
        return when (state) {
            BluetoothAdapter.STATE_OFF -> "OFF"
            BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
            BluetoothAdapter.STATE_ON -> "ON"
            BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
            else -> "UNKNOWN($state)"
        }
    }

    /**
     * Log current Bluetooth status for debugging
     */
    fun logBluetoothStatus() {
        Log.d(TAG, getDiagnostics())
    }
    
    fun cleanup() {
        try {
            application.unregisterReceiver(receiver)
            Log.d(TAG, "BroadcastReceiver unregistered")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver was not registered")
        }
    }
}

enum class BluetoothStatus {
    ENABLED,
    DISABLED, 
    NOT_SUPPORTED
}
