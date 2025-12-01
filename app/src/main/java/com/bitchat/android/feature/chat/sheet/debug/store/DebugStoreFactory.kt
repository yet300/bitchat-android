package com.bitchat.android.feature.chat.sheet.debug.store

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.ui.debug.ConnectedDevice
import com.bitchat.android.ui.debug.DebugMessage
import com.bitchat.android.ui.debug.DebugScanResult
import com.bitchat.android.ui.debug.DebugSettingsManager
import com.bitchat.android.ui.debug.PacketRelayStats
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent

internal class DebugStoreFactory(
    private val storeFactory: StoreFactory,
    private val debugSettingsManager: DebugSettingsManager,
    private val meshService: BluetoothMeshService
) : KoinComponent {

    fun create(): DebugStore =
        object : DebugStore, Store<DebugStore.Intent, DebugStore.State, DebugStore.Label> by storeFactory.create(
            name = "DebugStore",
            initialState = DebugStore.State(
                verboseLoggingEnabled = debugSettingsManager.verboseLoggingEnabled.value,
                gattServerEnabled = debugSettingsManager.gattServerEnabled.value,
                gattClientEnabled = debugSettingsManager.gattClientEnabled.value,
                packetRelayEnabled = debugSettingsManager.packetRelayEnabled.value,
                maxConnectionsOverall = debugSettingsManager.maxConnectionsOverall.value,
                maxServerConnections = debugSettingsManager.maxServerConnections.value,
                maxClientConnections = debugSettingsManager.maxClientConnections.value,
                debugMessages = debugSettingsManager.debugMessages.value,
                scanResults = debugSettingsManager.scanResults.value,
                connectedDevices = debugSettingsManager.connectedDevices.value,
                relayStats = debugSettingsManager.relayStats.value,
                seenPacketCapacity = debugSettingsManager.seenPacketCapacity.value,
                gcsMaxBytes = debugSettingsManager.gcsMaxBytes.value,
                gcsFprPercent = debugSettingsManager.gcsFprPercent.value,
                localAdapterAddress = try {
                    meshService.connectionManager.getLocalAdapterAddress()
                } catch (e: Exception) {
                    null
                }
            ),
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl
        ) {}

    private sealed interface Message {
        data class VerboseLoggingChanged(val enabled: Boolean) : Message
        data class GattServerChanged(val enabled: Boolean) : Message
        data class GattClientChanged(val enabled: Boolean) : Message
        data class PacketRelayChanged(val enabled: Boolean) : Message
        data class MaxConnectionsOverallChanged(val value: Int) : Message
        data class MaxServerConnectionsChanged(val value: Int) : Message
        data class MaxClientConnectionsChanged(val value: Int) : Message
        data class DebugMessagesUpdated(val messages: List<DebugMessage>) : Message
        data class ScanResultsUpdated(val results: List<DebugScanResult>) : Message
        data class ConnectedDevicesUpdated(val devices: List<ConnectedDevice>) : Message
        data class RelayStatsUpdated(val stats: PacketRelayStats) : Message
        data class SeenPacketCapacityChanged(val value: Int) : Message
        data class GcsMaxBytesChanged(val value: Int) : Message
        data class GcsFprPercentChanged(val value: Double) : Message
        data class MonitoringStateChanged(val isMonitoring: Boolean) : Message
    }

    private inner class ExecutorImpl :
        CoroutineExecutor<DebugStore.Intent, Nothing, DebugStore.State, Message, DebugStore.Label>() {

        override fun executeIntent(intent: DebugStore.Intent) {
            when (intent) {
                is DebugStore.Intent.ToggleVerboseLogging -> {
                    val newValue = !state().verboseLoggingEnabled
                    debugSettingsManager.setVerboseLoggingEnabled(newValue)
                    dispatch(Message.VerboseLoggingChanged(newValue))
                }

                is DebugStore.Intent.SetGattServerEnabled -> {
                    debugSettingsManager.setGattServerEnabled(intent.enabled)
                    dispatch(Message.GattServerChanged(intent.enabled))
                    scope.launch {
                        if (intent.enabled) {
                            meshService.connectionManager.startServer()
                        } else {
                            meshService.connectionManager.stopServer()
                        }
                    }
                }

                is DebugStore.Intent.SetGattClientEnabled -> {
                    debugSettingsManager.setGattClientEnabled(intent.enabled)
                    dispatch(Message.GattClientChanged(intent.enabled))
                    scope.launch {
                        if (intent.enabled) {
                            meshService.connectionManager.startClient()
                        } else {
                            meshService.connectionManager.stopClient()
                        }
                    }
                }

                is DebugStore.Intent.TogglePacketRelay -> {
                    val newValue = !state().packetRelayEnabled
                    debugSettingsManager.setPacketRelayEnabled(newValue)
                    dispatch(Message.PacketRelayChanged(newValue))
                }

                is DebugStore.Intent.UpdateMaxConnectionsOverall -> {
                    debugSettingsManager.setMaxConnectionsOverall(intent.value)
                    dispatch(Message.MaxConnectionsOverallChanged(intent.value))
                }

                is DebugStore.Intent.UpdateMaxServerConnections -> {
                    debugSettingsManager.setMaxServerConnections(intent.value)
                    dispatch(Message.MaxServerConnectionsChanged(intent.value))
                }

                is DebugStore.Intent.UpdateMaxClientConnections -> {
                    debugSettingsManager.setMaxClientConnections(intent.value)
                    dispatch(Message.MaxClientConnectionsChanged(intent.value))
                }

                is DebugStore.Intent.UpdateSeenPacketCapacity -> {
                    debugSettingsManager.setSeenPacketCapacity(intent.value)
                    dispatch(Message.SeenPacketCapacityChanged(intent.value))
                }

                is DebugStore.Intent.UpdateGcsMaxBytes -> {
                    debugSettingsManager.setGcsMaxBytes(intent.value)
                    dispatch(Message.GcsMaxBytesChanged(intent.value))
                }

                is DebugStore.Intent.UpdateGcsFprPercent -> {
                    debugSettingsManager.setGcsFprPercent(intent.value)
                    dispatch(Message.GcsFprPercentChanged(intent.value))
                }

                is DebugStore.Intent.ConnectToDevice -> {
                    meshService.connectionManager.connectToAddress(intent.address)
                }

                is DebugStore.Intent.DisconnectDevice -> {
                    meshService.connectionManager.disconnectAddress(intent.address)
                }

                is DebugStore.Intent.ClearDebugMessages -> {
                    debugSettingsManager.clearDebugMessages()
                }

                is DebugStore.Intent.StartMonitoring -> {
                    startMonitoring()
                }

                is DebugStore.Intent.StopMonitoring -> {
                    stopMonitoring()
                }
            }
        }

        private fun startMonitoring() {
            if (state().isMonitoring) return
            
            dispatch(Message.MonitoringStateChanged(true))
            
            // Collect state from DebugSettingsManager
            scope.launch {
                debugSettingsManager.debugMessages.collect { messages ->
                    dispatch(Message.DebugMessagesUpdated(messages))
                }
            }
            
            scope.launch {
                debugSettingsManager.scanResults.collect { results ->
                    dispatch(Message.ScanResultsUpdated(results))
                }
            }
            
            scope.launch {
                debugSettingsManager.connectedDevices.collect { devices ->
                    dispatch(Message.ConnectedDevicesUpdated(devices))
                }
            }
            
            scope.launch {
                debugSettingsManager.relayStats.collect { stats ->
                    dispatch(Message.RelayStatsUpdated(stats))
                }
            }
        }

        private fun stopMonitoring() {
            dispatch(Message.MonitoringStateChanged(false))
            // Coroutines will be cancelled when executor is disposed
        }
    }

    private object ReducerImpl : Reducer<DebugStore.State, Message> {
        override fun DebugStore.State.reduce(msg: Message): DebugStore.State =
            when (msg) {
                is Message.VerboseLoggingChanged -> copy(verboseLoggingEnabled = msg.enabled)
                is Message.GattServerChanged -> copy(gattServerEnabled = msg.enabled)
                is Message.GattClientChanged -> copy(gattClientEnabled = msg.enabled)
                is Message.PacketRelayChanged -> copy(packetRelayEnabled = msg.enabled)
                is Message.MaxConnectionsOverallChanged -> copy(maxConnectionsOverall = msg.value)
                is Message.MaxServerConnectionsChanged -> copy(maxServerConnections = msg.value)
                is Message.MaxClientConnectionsChanged -> copy(maxClientConnections = msg.value)
                is Message.DebugMessagesUpdated -> copy(debugMessages = msg.messages)
                is Message.ScanResultsUpdated -> copy(scanResults = msg.results)
                is Message.ConnectedDevicesUpdated -> copy(connectedDevices = msg.devices)
                is Message.RelayStatsUpdated -> copy(relayStats = msg.stats)
                is Message.SeenPacketCapacityChanged -> copy(seenPacketCapacity = msg.value)
                is Message.GcsMaxBytesChanged -> copy(gcsMaxBytes = msg.value)
                is Message.GcsFprPercentChanged -> copy(gcsFprPercent = msg.value)
                is Message.MonitoringStateChanged -> copy(isMonitoring = msg.isMonitoring)
            }
    }
}
