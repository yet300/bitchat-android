package com.yet.bitmessage.android.connectivity

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.app.domain.model.TransportKind
import com.app.domain.model.TransportState
import com.app.domain.model.TransportStatus
import com.app.domain.repository.ConnectivityRepository
import com.app.transport.mesh.BluetoothPermissions
import com.app.transport.mesh.MeshLifecycleController
import com.app.transport.mesh.aware.WifiAwareSupport
import com.app.transport.net.TorMode
import com.app.transport.net.TorPreferenceManager
import com.app.transport.nostr.NostrRelayManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay

/**
 * Reads live transport state from the platform (BLE adapter + permissions, Wi-Fi Aware, internet,
 * Tor preference) and performs the user-initiated repair via system intents / the Tor preference.
 *
 * State is polled while observed (the panel is short-lived), which is simpler and more robust than
 * juggling a broadcast receiver per radio.
 */
class AndroidConnectivityRepository(
    private val context: Context,
    private val torPreferenceManager: TorPreferenceManager,
    private val permissionRequester: RuntimePermissionRequester,
    private val meshLifecycle: MeshLifecycleController,
    private val nostrRelayManager: NostrRelayManager,
) : ConnectivityRepository {

    override fun observe(): Flow<List<TransportStatus>> = flow {
        while (true) {
            emit(snapshot())
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun snapshot(): List<TransportStatus> {
        val bluetooth = bluetoothState()
        val internet = internetState()
        return listOf(
            // Mesh peers ride BLE (primary) and Wi-Fi Aware; surfaced under Bluetooth as "nearby".
            TransportStatus(TransportKind.BLUETOOTH, bluetooth, count = meshPeerCount(bluetooth)),
            TransportStatus(TransportKind.WIFI_AWARE, wifiAwareState()),
            TransportStatus(TransportKind.INTERNET, internet, count = connectedRelayCount(internet)),
            TransportStatus(TransportKind.TOR, torState()),
        )
    }

    private fun meshPeerCount(state: TransportState): Int? =
        if (state == TransportState.ON) runCatching { meshLifecycle.activePeerCount() }.getOrNull() else null

    private fun connectedRelayCount(state: TransportState): Int? =
        if (state == TransportState.ON) {
            runCatching { nostrRelayManager.relays.value.count { it.isConnected } }.getOrNull()
        } else {
            null
        }

    private fun bluetoothState(): TransportState {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return TransportState.UNAVAILABLE
        }
        if (!hasBluetoothRuntimePermission()) return TransportState.PERMISSION_REQUIRED
        val adapter = bluetoothAdapter() ?: return TransportState.UNAVAILABLE
        return if (adapter.isEnabled) TransportState.ON else TransportState.OFF
    }

    private fun wifiAwareState(): TransportState {
        val status = WifiAwareSupport.evaluate(context)
        return when {
            !status.supported -> TransportState.UNAVAILABLE
            !hasWifiAwarePermission() -> TransportState.PERMISSION_REQUIRED
            status.available -> TransportState.ON
            else -> TransportState.OFF
        }
    }

    private fun internetState(): TransportState {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return TransportState.UNAVAILABLE
        val network = cm.activeNetwork ?: return TransportState.OFF
        val caps = cm.getNetworkCapabilities(network) ?: return TransportState.OFF
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            TransportState.ON
        } else {
            TransportState.OFF
        }
    }

    private fun torState(): TransportState =
        if (torPreferenceManager.get() == TorMode.OFF) TransportState.OFF else TransportState.ON

    override suspend fun enable(kind: TransportKind) {
        when (kind) {
            TransportKind.BLUETOOTH ->
                if (!hasBluetoothRuntimePermission()) requestOrSettings(bluetoothPermissions())
                else startSystemIntent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

            TransportKind.WIFI_AWARE ->
                if (!hasWifiAwarePermission()) {
                    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Manifest.permission.NEARBY_WIFI_DEVICES
                    } else {
                        Manifest.permission.ACCESS_FINE_LOCATION
                    }
                    requestOrSettings(listOf(permission))
                } else {
                    startSystemIntent(Settings.ACTION_WIFI_SETTINGS)
                }

            TransportKind.INTERNET -> startSystemIntent(Settings.ACTION_WIRELESS_SETTINGS)

            TransportKind.TOR -> {
                val next = if (torPreferenceManager.get() == TorMode.OFF) TorMode.ON else TorMode.OFF
                torPreferenceManager.set(next)
            }
        }
    }

    /**
     * Ask for the missing runtime [permissions] via the in-app system dialog; only fall back to the
     * app-settings deep link when the dialog can't help (permanently denied, or no Activity attached).
     * On grant the next [observe] poll reflects the new state — the radio itself (if off) is then a
     * separate one-tap "Enable" step.
     */
    private suspend fun requestOrSettings(permissions: List<String>) {
        when (permissionRequester.request(permissions)) {
            PermissionOutcome.GRANTED, PermissionOutcome.DENIED -> Unit
            PermissionOutcome.PERMANENTLY_DENIED, PermissionOutcome.NO_HOST -> openAppSettings()
        }
    }

    private fun bluetoothPermissions(): List<String> = BluetoothPermissions.toRequest()

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun hasBluetoothRuntimePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BluetoothPermissions.required().all { isGranted(it) }
        } else {
            isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

    private fun hasWifiAwarePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() = startSystemIntent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )

    private fun startSystemIntent(action: String, data: Uri? = null) {
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (data != null) this.data = data
        }
        runCatching { context.startActivity(intent) }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
    }
}
