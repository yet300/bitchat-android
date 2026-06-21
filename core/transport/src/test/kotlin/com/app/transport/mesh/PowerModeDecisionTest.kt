package com.app.transport.mesh

import com.app.transport.mesh.PowerManager.PowerMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the A2 fix: while the mesh foreground service is active the process must be treated
 * as foreground so background BLE discovery stays on the BALANCED 8s/2s duty cycle instead of
 * the POWER_SAVER 2s/28s cap. Battery downgrades must still apply regardless of the flag.
 *
 * Exercises the pure decision function so no Android Context is required.
 */
class PowerModeDecisionTest {

    @Test
    fun `mesh service active in background on normal battery stays BALANCED, not POWER_SAVER`() {
        val mode = PowerManager.computePowerMode(
            batteryLevel = 80,
            isCharging = false,
            isAppInBackground = true,
            meshServiceActive = true,
        )
        assertEquals(PowerMode.BALANCED, mode)
    }

    @Test
    fun `background without mesh service on normal battery is POWER_SAVER (unchanged)`() {
        val mode = PowerManager.computePowerMode(
            batteryLevel = 80,
            isCharging = false,
            isAppInBackground = true,
            meshServiceActive = false,
        )
        assertEquals(PowerMode.POWER_SAVER, mode)
    }

    @Test
    fun `low battery still downgrades to POWER_SAVER even with mesh service active`() {
        val mode = PowerManager.computePowerMode(
            batteryLevel = 15, // <= LOW_BATTERY (20)
            isCharging = false,
            isAppInBackground = true,
            meshServiceActive = true,
        )
        assertEquals(PowerMode.POWER_SAVER, mode)
    }

    @Test
    fun `critical battery still downgrades to ULTRA_LOW_POWER even with mesh service active`() {
        val mode = PowerManager.computePowerMode(
            batteryLevel = 5, // <= CRITICAL_BATTERY (10)
            isCharging = false,
            isAppInBackground = true,
            meshServiceActive = true,
        )
        assertEquals(PowerMode.ULTRA_LOW_POWER, mode)
    }

    @Test
    fun `critical battery in background without mesh service is ULTRA_LOW_POWER (preserved)`() {
        val mode = PowerManager.computePowerMode(
            batteryLevel = 5,
            isCharging = false,
            isAppInBackground = true,
            meshServiceActive = false,
        )
        assertEquals(PowerMode.ULTRA_LOW_POWER, mode)
    }

    @Test
    fun `actual foreground on normal battery is BALANCED regardless of flag`() {
        val mode = PowerManager.computePowerMode(
            batteryLevel = 80,
            isCharging = false,
            isAppInBackground = false,
            meshServiceActive = false,
        )
        assertEquals(PowerMode.BALANCED, mode)
    }
}
