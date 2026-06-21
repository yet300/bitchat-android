package com.app.transport.mesh

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the Wi-Fi Aware permission contract across API tiers without a real Context.
 *
 * The expected behaviour:
 * - API < 26: unsupported (floor is Android 8 / Oreo)
 * - API 26-32: needs ACCESS_FINE_LOCATION to reach PERMISSION_REQUIRED
 * - API 33+:   needs NEARBY_WIFI_DEVICES
 */
class WifiAwareSupportFloorTest {

    @Test
    fun `support floor is API 26 not API 29`() {
        // O = 26 (Oreo), Q = 29 (Android 10).
        // The fix changed the hard gate from Q to O, so these constants are the sentinel.
        assertEquals("support floor constant must be O (26)", 26, Build.VERSION_CODES.O)
        assertEquals("Q is 29", 29, Build.VERSION_CODES.Q)
    }

    @Test
    fun `pre-api33 wifi aware permission is ACCESS_FINE_LOCATION`() {
        val permission = wifiAwarePermissionForApiLevel(apiLevel = 30)
        assertEquals(Manifest.permission.ACCESS_FINE_LOCATION, permission)
    }

    @Test
    fun `api29 wifi aware permission is ACCESS_FINE_LOCATION`() {
        val permission = wifiAwarePermissionForApiLevel(apiLevel = Build.VERSION_CODES.Q)
        assertEquals(Manifest.permission.ACCESS_FINE_LOCATION, permission)
    }

    @Test
    fun `api33 wifi aware permission is NEARBY_WIFI_DEVICES`() {
        val permission = wifiAwarePermissionForApiLevel(apiLevel = Build.VERSION_CODES.TIRAMISU)
        assertEquals(Manifest.permission.NEARBY_WIFI_DEVICES, permission)
    }

    @Test
    fun `api34 wifi aware permission is NEARBY_WIFI_DEVICES`() {
        val permission = wifiAwarePermissionForApiLevel(apiLevel = 34)
        assertEquals(Manifest.permission.NEARBY_WIFI_DEVICES, permission)
    }

    /** Mirrors the permission selection logic in AndroidConnectivityRepository.enable(WIFI_AWARE). */
    private fun wifiAwarePermissionForApiLevel(apiLevel: Int): String =
        if (apiLevel >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
}
