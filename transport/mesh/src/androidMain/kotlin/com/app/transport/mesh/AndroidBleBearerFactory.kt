package com.app.transport.mesh

import android.content.Context
import com.app.transport.MeshTelemetry

/**
 * Builds the shared [BleBearer] over the Android radio stack ([BluetoothConnectionManager], which is
 * internal to this module). Replaces BleBearer's former Android-typed convenience constructor so the
 * facade can live in commonMain while :shared keeps a single public entry point.
 *
 * The ownership predicate excludes the Wi-Fi Aware `aware:` address namespace, so BLE peer bindings
 * never capture links owned by [WifiAwareBearer].
 */
fun createAndroidBleBearer(
    context: Context,
    myPeerID: String,
    debugSettingsManager: MeshTelemetry,
    fragmentManager: FragmentManager?,
    transferProgressManager: TransferProgressManager,
): BleBearer = BleBearer(
    myPeerID = myPeerID,
    debugSettingsManager = debugSettingsManager,
    connectionManagerFactory = { pid ->
        BluetoothConnectionManager(
            context = context.applicationContext,
            myPeerID = pid,
            debugSettingsManager = debugSettingsManager,
            fragmentManager = fragmentManager,
            transferProgressManager = transferProgressManager,
        )
    },
    ownsLinkAddress = { !it.startsWith(WifiAwareBearer.LINK_PREFIX) },
)
