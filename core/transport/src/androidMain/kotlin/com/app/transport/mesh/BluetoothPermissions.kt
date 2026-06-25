package com.app.transport.mesh

import android.Manifest
import android.os.Build

/**
 * Single source of truth for BLE permission sets.
 * Both the gate (BluetoothPermissionManager) and the request side
 * (AndroidConnectivityRepository) derive from here so they cannot drift.
 *
 * On API 31+, BLUETOOTH_SCAN is declared in the manifest with
 * usesPermissionFlags="neverForLocation", so location is NOT required for BLE.
 */
object BluetoothPermissions {

    /**
     * Permissions that must ALL be granted before the BLE mesh can start.
     */
    fun required(apiLevel: Int = Build.VERSION.SDK_INT): Set<String> =
        if (apiLevel >= Build.VERSION_CODES.S) {
            setOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            )
        } else {
            // BLUETOOTH + BLUETOOTH_ADMIN are install-time grants; only location is runtime.
            // The gate accepts FINE OR COARSE — [required] lists the stricter one for containment tests.
            setOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /**
     * Permissions to request at runtime to satisfy the [required] gate.
     * Pre-31: includes COARSE alongside FINE since either satisfies the gate.
     */
    fun toRequest(apiLevel: Int = Build.VERSION.SDK_INT): List<String> =
        if (apiLevel >= Build.VERSION_CODES.S) {
            required(apiLevel).toList()
        } else {
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }
}
