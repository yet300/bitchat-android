package com.app.transport.mesh

/**
 * commonMain port of the reference iOS `BLEScanDutyPolicy`. Decides whether the central should scan
 * continuously or duty-cycle (on/off) to save power. Pure function; each platform supplies its own
 * executor (iOS toggles `scanForPeripherals`/`stopScan`; Android already duty-cycles via its
 * PowerManager, so this expresses the same policy platform-independently).
 */
sealed interface BleScanDutyPlan {
    data object Continuous : BleScanDutyPlan
    data class DutyCycle(val onMs: Long, val offMs: Long) : BleScanDutyPlan
}

object BleScanDutyPolicy {
    fun plan(
        dutyEnabled: Boolean,
        appIsActive: Boolean,
        connectedCount: Int,
        hasRecentTraffic: Boolean,
        config: BleRadioConfig = BleRadioConfig(),
    ): BleScanDutyPlan {
        // Stay continuous while sparse or while traffic is flowing — losing discovery windows there
        // costs more than the radio time.
        val forceContinuous = connectedCount <= 2 || hasRecentTraffic
        val shouldDutyCycle = dutyEnabled && appIsActive && connectedCount > 0 && !forceContinuous
        if (!shouldDutyCycle) return BleScanDutyPlan.Continuous

        return if (connectedCount >= config.highDegreeThreshold) {
            BleScanDutyPlan.DutyCycle(config.dutyOnDenseMs, config.dutyOffDenseMs)
        } else {
            BleScanDutyPlan.DutyCycle(config.dutyOnMs, config.dutyOffMs)
        }
    }
}
