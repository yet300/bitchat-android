package com.yet.bitmessage.feature.debug

import com.app.domain.model.PacketLogEntry
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value

/**
 * Debug tooling (P24): a root-level developer screen reached from Settings. GATT role toggles,
 * verbose-logging / packet-relay switches, the seen-packet capacity, and a live BLE/mesh status
 * dump. Navigation (close) is owned by the root.
 */
interface DebugComponent {

    val model: Value<Model>

    fun onGattServerToggled(enabled: Boolean)
    fun onGattClientToggled(enabled: Boolean)
    fun onVerboseToggled(enabled: Boolean)
    fun onPacketRelayToggled(enabled: Boolean)
    fun onSeenCapacityChanged(value: Int)
    fun onRefreshStatus()
    fun onCloseClicked()

    data class Model(
        val gattServerEnabled: Boolean,
        val gattClientEnabled: Boolean,
        val verboseLogging: Boolean,
        val packetRelayEnabled: Boolean,
        val seenPacketCapacity: Int,
        val status: String,
        val packetLog: List<PacketLogEntry>,
    )

    fun interface Factory {
        fun create(componentContext: ComponentContext, onClose: () -> Unit): DebugComponent
    }
}
