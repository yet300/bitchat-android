package com.bitchat.android.feature.chat.sheet.debug

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.bitchat.android.core.common.asValue
import com.bitchat.android.feature.chat.sheet.debug.integration.stateToModel
import com.bitchat.android.feature.chat.sheet.debug.store.DebugStore
import com.bitchat.android.feature.chat.sheet.debug.store.DebugStoreFactory
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.ui.debug.DebugSettingsManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Default implementation of DebugComponent using Store pattern
 */
class DefaultDebugComponent(
    componentContext: ComponentContext,
    private val onDismissRequest: () -> Unit
) : DebugComponent, ComponentContext by componentContext, KoinComponent {

    private val storeFactory: StoreFactory by inject()
    private val debugSettingsManager: DebugSettingsManager by inject()
    private val meshService: BluetoothMeshService by inject()

    private val store = instanceKeeper.getStore {
        DebugStoreFactory(
            storeFactory = storeFactory,
            debugSettingsManager = debugSettingsManager,
            meshService = meshService
        ).create()
    }

    override val model: Value<DebugComponent.Model> = store.asValue().map(stateToModel)

    init {
        // Start monitoring when component is created
        store.accept(DebugStore.Intent.StartMonitoring)
    }

    override fun onToggleVerboseLogging() {
        store.accept(DebugStore.Intent.ToggleVerboseLogging)
    }

    override fun onToggleGattServer() {
        val currentState = store.state.gattServerEnabled
        store.accept(DebugStore.Intent.SetGattServerEnabled(!currentState))
    }

    override fun onToggleGattClient() {
        val currentState = store.state.gattClientEnabled
        store.accept(DebugStore.Intent.SetGattClientEnabled(!currentState))
    }

    override fun onTogglePacketRelay() {
        store.accept(DebugStore.Intent.TogglePacketRelay)
    }

    override fun onUpdateMaxConnectionsOverall(value: Int) {
        store.accept(DebugStore.Intent.UpdateMaxConnectionsOverall(value))
    }

    override fun onUpdateMaxServerConnections(value: Int) {
        store.accept(DebugStore.Intent.UpdateMaxServerConnections(value))
    }

    override fun onUpdateMaxClientConnections(value: Int) {
        store.accept(DebugStore.Intent.UpdateMaxClientConnections(value))
    }

    override fun onUpdateSeenPacketCapacity(value: Int) {
        store.accept(DebugStore.Intent.UpdateSeenPacketCapacity(value))
    }

    override fun onUpdateGcsMaxBytes(value: Int) {
        store.accept(DebugStore.Intent.UpdateGcsMaxBytes(value))
    }

    override fun onUpdateGcsFprPercent(value: Double) {
        store.accept(DebugStore.Intent.UpdateGcsFprPercent(value))
    }

    override fun onConnectToDevice(address: String) {
        store.accept(DebugStore.Intent.ConnectToDevice(address))
    }

    override fun onDisconnectDevice(address: String) {
        store.accept(DebugStore.Intent.DisconnectDevice(address))
    }
    
    override fun onClearDebugMessages() {
        store.accept(DebugStore.Intent.ClearDebugMessages)
    }

    override fun onDismiss() {
        // Stop monitoring when dismissed
        store.accept(DebugStore.Intent.StopMonitoring)
        onDismissRequest()
    }
}
