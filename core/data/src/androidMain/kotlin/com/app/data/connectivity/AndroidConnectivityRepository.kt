package com.app.data.connectivity

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import com.app.common.permission.AppPermission
import com.app.common.permission.PermissionController
import com.app.domain.model.TransportKind
import com.app.domain.model.TransportState
import com.app.domain.model.TransportStatus
import com.app.domain.repository.ConnectivityRepository
import com.app.data.tor.TorUserPreferenceManager
import com.app.transport.mesh.MeshLifecycleController
import com.app.transport.mesh.aware.WifiAwareSupport
import com.app.transport.nostr.NostrRelayManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    private val torUserPreference: TorUserPreferenceManager,
    private val permissionController: PermissionController,
    private val meshLifecycle: MeshLifecycleController,
    private val nostrRelayManager: NostrRelayManager,
) : ConnectivityRepository {

    override fun observe(): Flow<List<TransportStatus>> = flow {
        while (true) {
            emit(snapshot())
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun snapshot(): List<TransportStatus> {
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

    private suspend fun bluetoothState(): TransportState {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return TransportState.UNAVAILABLE
        }
        if (!hasBluetoothRuntimePermission()) return TransportState.PERMISSION_REQUIRED
        val adapter = bluetoothAdapter() ?: return TransportState.UNAVAILABLE
        return if (adapter.isEnabled) TransportState.ON else TransportState.OFF
    }

    private suspend fun wifiAwareState(): TransportState {
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
        if (!torUserPreference.get()) TransportState.OFF else TransportState.ON

    override suspend fun enable(kind: TransportKind) {
        when (kind) {
            // Grant maps to the SDK-appropriate BLE permissions (scan/connect + advertise, or legacy
            // location) and handles the permanently-denied -> app-settings fallback itself.
            TransportKind.BLUETOOTH ->
                if (!hasBluetoothRuntimePermission()) {
                    permissionController.requestPermissions(
                        listOf(AppPermission.Bluetooth, AppPermission.BluetoothAdvertise),
                    )
                } else {
                    startSystemIntent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                }

            TransportKind.WIFI_AWARE ->
                if (!hasWifiAwarePermission()) permissionController.requestPermission(AppPermission.NearbyWifi)
                else startSystemIntent(Settings.ACTION_WIFI_SETTINGS)

            TransportKind.INTERNET -> startSystemIntent(Settings.ACTION_WIRELESS_SETTINGS)

            TransportKind.TOR -> {
                torUserPreference.set(!torUserPreference.get())
            }
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // Status read via the app's PermissionController (Grant checkStatus) — it resolves the SDK-version
    // permission set (BLE scan/connect + advertise, or legacy location) internally.
    private suspend fun hasBluetoothRuntimePermission(): Boolean =
        permissionController.observeGranted(AppPermission.Bluetooth).first() &&
            permissionController.observeGranted(AppPermission.BluetoothAdvertise).first()

    private suspend fun hasWifiAwarePermission(): Boolean =
        permissionController.observeGranted(AppPermission.NearbyWifi).first()

    private fun startSystemIntent(action: String) {
        val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { context.startActivity(intent) }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
    }
}
