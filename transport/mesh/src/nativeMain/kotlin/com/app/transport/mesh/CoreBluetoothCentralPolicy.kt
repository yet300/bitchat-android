@file:OptIn(ExperimentalTime::class)

package com.app.transport.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

/**
 * The CoreBluetooth operations the connection policy needs. The manager implements it; the policy
 * stays free of CoreBluetooth types (it only knows peripheral-ID strings), so all the decision logic
 * is the host-tested commonMain [BleConnectionScheduler].
 */
internal interface CentralConnectionOps {
    /** Issue a connect to the discovered peripheral with this id. */
    fun connect(peripheralID: String)
    /** Cancel a stale connecting/connected link (the scheduler saw a duplicate discovery). */
    fun cancelStale(peripheralID: String)
    fun connectedOrConnectingCount(): Int
    fun connectedCount(): Int
    fun isConnectingOrConnected(peripheralID: String): Boolean
    /** Read RSSI on every connected peripheral (the callback emits RssiChanged). */
    fun readRssiForConnected()
    fun setScanning(active: Boolean)
    fun appIsActive(): Boolean
    fun hasRecentTraffic(): Boolean
}

/**
 * Drives the reference iOS connection strategy on top of the commonMain [BleConnectionScheduler]:
 * caps central links, RSSI-gates and rate-limits connects, backs off on timeouts, periodically reads
 * RSSI (so PeerManager stops being blind on iOS), and applies the [BleScanDutyPolicy]. Owns the
 * maintenance + duty-cycle coroutines; the manager forwards discovery/connect/disconnect events and
 * implements the raw CoreBluetooth ops.
 *
 * This is the split the design called for: the CoreBluetooth glue stays in the manager, the policy
 * and its timers live here, and the actual decisions are shared, host-tested commonMain code.
 */
internal class CoreBluetoothCentralPolicy(
    private val scope: CoroutineScope,
    private val ops: CentralConnectionOps,
    private val config: BleRadioConfig = BleRadioConfig(),
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val scheduler = BleConnectionScheduler(config)

    private var maintenanceJob: Job? = null
    private var dutyJob: Job? = null
    private var currentPlan: BleScanDutyPlan? = null

    /** A peripheral advertising the mesh service was discovered. */
    fun onDiscovered(peripheralID: String, rssi: Int, connectable: Boolean) {
        val now = nowMs()
        val state = if (ops.isConnectingOrConnected(peripheralID)) {
            BleConnectionScheduler.PeripheralConnectionState.CONNECTED
        } else {
            BleConnectionScheduler.PeripheralConnectionState.DISCONNECTED
        }
        val decision = scheduler.handleDiscovery(
            BleConnectionScheduler.Candidate(peripheralID, rssi, connectable, now),
            connectedOrConnectingCount = ops.connectedOrConnectingCount(),
            existingState = null,
            peripheralState = state,
            nowMs = now,
        )
        when (decision) {
            is BleConnectionScheduler.DiscoveryDecision.ConnectNow -> issueConnect(peripheralID)
            is BleConnectionScheduler.DiscoveryDecision.CancelStaleConnection -> ops.cancelStale(peripheralID)
            // Queued / ScheduleRetry / Ignore: the candidate is parked in the scheduler; the
            // maintenance loop (or the next connect-completed event) will drain it when eligible.
            else -> Unit
        }
    }

    fun onConnected(peripheralID: String) {
        scheduler.recordConnectionSuccess(peripheralID)
        drain()
    }

    fun onConnectFailed(peripheralID: String) {
        scheduler.recordConnectionTimeout(peripheralID, nowMs())
        drain()
    }

    fun onDisconnected(peripheralID: String) {
        scheduler.recordDisconnectError(peripheralID, nowMs())
        drain()
    }

    fun start() {
        if (maintenanceJob?.isActive == true) return
        maintenanceJob = scope.launch {
            while (isActive) {
                delay(MAINTENANCE_INTERVAL_MS.milliseconds)
                val now = nowMs()
                scheduler.pruneConnectionTimeouts(now - config.connectTimeoutBackoffWindowMs)
                scheduler.updateRssiThreshold(ops.connectedCount(), ops.connectedOrConnectingCount(), now)
                ops.readRssiForConnected()
                drain()
                applyScanDuty()
            }
        }
    }

    fun stop() {
        maintenanceJob?.cancel(); maintenanceJob = null
        dutyJob?.cancel(); dutyJob = null
        currentPlan = null
        scheduler.reset()
    }

    private fun issueConnect(peripheralID: String) {
        scheduler.recordConnectionAttempt(nowMs())
        ops.connect(peripheralID)
    }

    private fun drain() {
        val decision = scheduler.nextCandidate(
            connectedOrConnectingCount = ops.connectedOrConnectingCount(),
            isAlreadyConnectingOrConnected = ops::isConnectingOrConnected,
            nowMs = nowMs(),
        )
        if (decision is BleConnectionScheduler.QueueDecision.Connect) {
            issueConnect(decision.candidate.peripheralID)
        }
        // RetryAfter / None: the maintenance loop retries on its next tick.
    }

    private fun applyScanDuty() {
        val plan = BleScanDutyPolicy.plan(
            dutyEnabled = true,
            appIsActive = ops.appIsActive(),
            connectedCount = ops.connectedCount(),
            hasRecentTraffic = ops.hasRecentTraffic(),
            config = config,
        )
        if (plan == currentPlan) return
        currentPlan = plan
        dutyJob?.cancel(); dutyJob = null
        when (plan) {
            is BleScanDutyPlan.Continuous -> ops.setScanning(true)
            is BleScanDutyPlan.DutyCycle -> {
                dutyJob = scope.launch {
                    while (isActive) {
                        ops.setScanning(true); delay(plan.onMs.milliseconds)
                        ops.setScanning(false); delay(plan.offMs.milliseconds)
                    }
                }
            }
        }
    }

    private companion object {
        const val MAINTENANCE_INTERVAL_MS = 1_000L
    }
}
