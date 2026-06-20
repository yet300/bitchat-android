package com.yet.bitmessage.feature.debug.store

import com.app.domain.model.PacketLogEntry
import com.arkivanov.mvikotlin.core.store.Store

internal interface DebugStore : Store<DebugStore.Intent, DebugStore.State, Nothing> {

    data class State(
        val gattServerEnabled: Boolean = true,
        val gattClientEnabled: Boolean = true,
        val verboseLogging: Boolean = false,
        val packetRelayEnabled: Boolean = true,
        val seenPacketCapacity: Int = 0,
        val status: String = "",
        val packetLog: List<PacketLogEntry> = emptyList(),
    )

    sealed interface Intent {
        data class SetGattServer(val enabled: Boolean) : Intent
        data class SetGattClient(val enabled: Boolean) : Intent
        data class SetVerbose(val enabled: Boolean) : Intent
        data class SetPacketRelay(val enabled: Boolean) : Intent
        data class SetSeenCapacity(val value: Int) : Intent
        data object RefreshStatus : Intent
    }

    sealed interface Action {
        data object Load : Action
    }

    sealed interface Msg {
        data class Loaded(val state: State) : Msg
        data class GattServerChanged(val enabled: Boolean) : Msg
        data class GattClientChanged(val enabled: Boolean) : Msg
        data class VerboseChanged(val enabled: Boolean) : Msg
        data class PacketRelayChanged(val enabled: Boolean) : Msg
        data class SeenCapacityChanged(val value: Int) : Msg
        data class StatusChanged(val status: String) : Msg
        data class PacketLogChanged(val entries: List<PacketLogEntry>) : Msg
    }
}
