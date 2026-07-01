package com.app.common.permission

import kotlinx.coroutines.flow.Flow

/**
 * Single app-wide seam for runtime permission status and requests — the permission counterpart of
 * [com.app.common.AppDispatchers]. Callers inject this one port and gate on an [AppPermission] value;
 * the concrete implementation (Grant-backed, in :core:common) maps to the platform permission, owns
 * the system dialog / app-settings fallback and the SDK-version differences, so no platform permission
 * types leak into caller code.
 */
interface PermissionController {

    /** Emits the current grant state of [permission] while collected. */
    fun observeGranted(permission: AppPermission): Flow<Boolean>

    /**
     * Shows the system dialog for [permission] (or opens app settings when permanently denied) and
     * returns whether it is granted afterwards. No-op returning `true` if already granted.
     */
    suspend fun requestPermission(permission: AppPermission): Boolean

    /**
     * Requests several [permissions] in a single grouped dialog (e.g. the Bluetooth scan/connect +
     * advertise pair). Opens app settings if any is permanently denied. Returns `true` only if all
     * are granted afterwards.
     */
    suspend fun requestPermissions(permissions: List<AppPermission>): Boolean
}

/**
 * App-level runtime permissions the UI can gate on, independent of any platform permission library.
 * The [PermissionController] implementation maps each entry to the concrete platform permission(s)
 * for the running OS version. Add a new entry here (and its mapping) when another capability needs
 * runtime gating.
 */
sealed interface AppPermission {
    data object Camera : AppPermission
    data object Microphone : AppPermission
    data object Notifications : AppPermission

    /** BLE scan + connect (Android 12+ BLUETOOTH_SCAN/CONNECT, or location on older OS). */
    data object Bluetooth : AppPermission

    /** BLE advertise (Android 12+ BLUETOOTH_ADVERTISE) — the mesh peripheral role. */
    data object BluetoothAdvertise : AppPermission

    /** Wi-Fi Aware neighbour discovery (Android 13+ NEARBY_WIFI_DEVICES, or location on older OS). */
    data object NearbyWifi : AppPermission
}
