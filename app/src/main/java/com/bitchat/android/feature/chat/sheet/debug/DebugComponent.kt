package com.bitchat.android.feature.chat.sheet.debug

import com.arkivanov.decompose.value.Value
import com.bitchat.android.ui.debug.ConnectedDevice
import com.bitchat.android.ui.debug.DebugMessage
import com.bitchat.android.ui.debug.DebugScanResult
import com.bitchat.android.ui.debug.PacketRelayStats

/**
 * Debug settings component interface
 */
interface DebugComponent {
    
    val model: Value<Model>
    
    // Actions
    fun onToggleVerboseLogging()
    fun onToggleGattServer()
    fun onToggleGattClient()
    fun onTogglePacketRelay()
    fun onUpdateMaxConnectionsOverall(value: Int)
    fun onUpdateMaxServerConnections(value: Int)
    fun onUpdateMaxClientConnections(value: Int)
    fun onUpdateSeenPacketCapacity(value: Int)
    fun onUpdateGcsMaxBytes(value: Int)
    fun onUpdateGcsFprPercent(value: Double)
    fun onConnectToDevice(address: String)
    fun onDisconnectDevice(address: String)
    fun onClearDebugMessages()
    fun onDismiss()
    
    data class Model(
        val verboseLoggingEnabled: Boolean,
        val gattServerEnabled: Boolean,
        val gattClientEnabled: Boolean,
        val packetRelayEnabled: Boolean,
        val maxConnectionsOverall: Int,
        val maxServerConnections: Int,
        val maxClientConnections: Int,
        val debugMessages: List<DebugMessage>,
        val scanResults: List<DebugScanResult>,
        val connectedDevices: List<ConnectedDevice>,
        val relayStats: PacketRelayStats,
        val seenPacketCapacity: Int,
        val gcsMaxBytes: Int,
        val gcsFprPercent: Double,
        val localAdapterAddress: String?,
    )
}
