package com.app.transport.mesh

import com.app.common.AppDispatchers
import com.app.transport.MeshTelemetry

/**
 * Builds the shared [BleBearer] over the Apple radio stack ([CoreBluetoothConnectionManager], which
 * is internal to this module) — the native counterpart of [createAndroidBleBearer]. The future iOS
 * :shared graph calls this with the live identity/telemetry, exactly as AndroidDataBindings calls
 * createAndroidBleBearer.
 *
 * No address-ownership filter is supplied (Wi-Fi Aware does not exist on iOS), so the bearer owns
 * every CoreBluetooth link address.
 */
fun createNativeBleBearer(
    myPeerID: String,
    debugSettingsManager: MeshTelemetry,
    fragmentManager: FragmentManager?,
    transferProgressManager: TransferProgressManager,
    dispatchers: AppDispatchers = AppDispatchers(),
): BleBearer = BleBearer(
    myPeerID = myPeerID,
    debugSettingsManager = debugSettingsManager,
    connectionManagerFactory = { pid ->
        CoreBluetoothConnectionManager(
            myPeerID = pid,
            fragmentManager = fragmentManager,
            transferProgressManager = transferProgressManager,
            dispatchers = dispatchers,
        )
    },
)
