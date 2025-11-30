package com.bitchat.android.feature.debug.store

import com.arkivanov.mvikotlin.core.store.Store
import com.bitchat.android.feature.debug.store.DebugStore.*
import com.bitchat.android.ui.debug.ConnectedDevice
import com.bitchat.android.ui.debug.DebugMessage
import com.bitchat.android.ui.debug.DebugScanResult
import com.bitchat.android.ui.debug.PacketRelayStats

interface DebugStore : Store<Intent, State, Label> {

    sealed interface Intent {
        data object ToggleVerboseLogging : Intent
        data class SetGattServerEnabled(val enabled: Boolean) : Intent
        data class SetGattClientEnabled(val enabled: Boolean) : Intent
        data object TogglePacketRelay : Intent
        data class UpdateMaxConnectionsOverall(val value: Int) : Intent
        data class UpdateMaxServerConnections(val value: Int) : Intent
        data class UpdateMaxClientConnections(val value: Int) : Intent
        data class UpdateSeenPacketCapacity(val value: Int) : Intent
        data class UpdateGcsMaxBytes(val value: Int) : Intent
        data class UpdateGcsFprPercent(val value: Double) : Intent
        data class ConnectToDevice(val address: String) : Intent
        data class DisconnectDevice(val address: String) : Intent
        data object ClearDebugMessages : Intent
        data object StartMonitoring : Intent
        data object StopMonitoring : Intent
    }

    data class State(
        val verboseLoggingEnabled: Boolean = false,
        val gattServerEnabled: Boolean = true,
        val gattClientEnabled: Boolean = true,
        val packetRelayEnabled: Boolean = true,
        val maxConnectionsOverall: Int = 8,
        val maxServerConnections: Int = 8,
        val maxClientConnections: Int = 8,
        val debugMessages: List<DebugMessage> = emptyList(),
        val scanResults: List<DebugScanResult> = emptyList(),
        val connectedDevices: List<ConnectedDevice> = emptyList(),
        val relayStats: PacketRelayStats = PacketRelayStats(),
        val seenPacketCapacity: Int = 10000,
        val gcsMaxBytes: Int = 50000,
        val gcsFprPercent: Double = 0.001,
        val localAdapterAddress: String? = null,
        val isMonitoring: Boolean = false
    )

    sealed interface Label {
        data class Error(val message: String) : Label
    }
}
