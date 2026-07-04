package com.app.transport.mesh

import android.Manifest
import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that BluetoothPermissions.toRequest() ⊇ BluetoothPermissions.required() for each
 * API tier. This is the invariant that, if broken, makes the mesh gate impossible to satisfy
 * (the user is never asked for a permission the gate demands).
 */
class BluetoothPermissionContractTest {

    @Test
    fun `api31+ requested set is a superset of required set`() {
        val required = BluetoothPermissions.required(apiLevel = Build.VERSION_CODES.S)
        val requested = BluetoothPermissions.toRequest(apiLevel = Build.VERSION_CODES.S).toSet()
        assertTrue(
            "toRequest must contain all required on API 31+; required=$required requested=$requested",
            requested.containsAll(required),
        )
    }

    @Test
    fun `pre-api31 requested set covers the location gate`() {
        val required = BluetoothPermissions.required(apiLevel = 30)
        val requested = BluetoothPermissions.toRequest(apiLevel = 30).toSet()
        assertTrue(
            "toRequest must satisfy the pre-31 location gate; required=$required requested=$requested",
            required.any { it in requested },
        )
    }

    @Test
    fun `api31+ required set excludes location permissions`() {
        val required = BluetoothPermissions.required(apiLevel = Build.VERSION_CODES.S)
        assertFalse(
            "Location must not be required on API 31+ (neverForLocation on BLUETOOTH_SCAN)",
            Manifest.permission.ACCESS_FINE_LOCATION in required,
        )
        assertFalse(
            "Location must not be required on API 31+ (neverForLocation on BLUETOOTH_SCAN)",
            Manifest.permission.ACCESS_COARSE_LOCATION in required,
        )
    }

    @Test
    fun `api31+ required set includes BLUETOOTH_ADVERTISE`() {
        val required = BluetoothPermissions.required(apiLevel = Build.VERSION_CODES.S)
        assertTrue(
            "BLUETOOTH_ADVERTISE is required for BLE advertising on API 31+",
            Manifest.permission.BLUETOOTH_ADVERTISE in required,
        )
    }

    @Test
    fun `api31+ required set includes BLUETOOTH_CONNECT and BLUETOOTH_SCAN`() {
        val required = BluetoothPermissions.required(apiLevel = Build.VERSION_CODES.S)
        assertTrue("BLUETOOTH_CONNECT must be required", Manifest.permission.BLUETOOTH_CONNECT in required)
        assertTrue("BLUETOOTH_SCAN must be required", Manifest.permission.BLUETOOTH_SCAN in required)
    }
}
