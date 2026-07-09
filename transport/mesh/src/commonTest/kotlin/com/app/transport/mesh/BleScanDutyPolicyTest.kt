package com.app.transport.mesh

import kotlin.test.Test
import kotlin.test.assertEquals

/** Host tests for the commonMain [BleScanDutyPolicy] port. */
class BleScanDutyPolicyTest {

    @Test
    fun sparseGraphScansContinuously() {
        assertEquals(
            BleScanDutyPlan.Continuous,
            BleScanDutyPolicy.plan(dutyEnabled = true, appIsActive = true, connectedCount = 2, hasRecentTraffic = false),
        )
    }

    @Test
    fun recentTrafficForcesContinuous() {
        assertEquals(
            BleScanDutyPlan.Continuous,
            BleScanDutyPolicy.plan(dutyEnabled = true, appIsActive = true, connectedCount = 8, hasRecentTraffic = true),
        )
    }

    @Test
    fun connectedAndQuietDutyCycles() {
        val plan = BleScanDutyPolicy.plan(dutyEnabled = true, appIsActive = true, connectedCount = 3, hasRecentTraffic = false)
        assertEquals(BleScanDutyPlan.DutyCycle(5_000L, 10_000L), plan)
    }

    @Test
    fun denseGraphUsesDenseCadence() {
        val plan = BleScanDutyPolicy.plan(dutyEnabled = true, appIsActive = true, connectedCount = 6, hasRecentTraffic = false)
        assertEquals(BleScanDutyPlan.DutyCycle(3_000L, 15_000L), plan)
    }

    @Test
    fun disabledOrBackgroundStaysContinuous() {
        assertEquals(
            BleScanDutyPlan.Continuous,
            BleScanDutyPolicy.plan(dutyEnabled = false, appIsActive = true, connectedCount = 5, hasRecentTraffic = false),
        )
        assertEquals(
            BleScanDutyPlan.Continuous,
            BleScanDutyPolicy.plan(dutyEnabled = true, appIsActive = false, connectedCount = 5, hasRecentTraffic = false),
        )
    }
}
