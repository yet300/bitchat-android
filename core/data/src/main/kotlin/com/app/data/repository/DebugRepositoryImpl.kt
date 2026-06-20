package com.app.data.repository

import com.app.domain.model.PacketLogEntry
import com.app.domain.model.PacketLogKind
import com.app.domain.repository.DebugRepository
import com.app.transport.debug.DebugMessage
import com.app.transport.debug.DebugPreferenceManager
import com.app.transport.debug.DebugSettingsManager
import com.app.transport.mesh.BluetoothMeshService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Debug controls over the existing transport debug surfaces: [DebugPreferenceManager] for persisted
 * options, the BLE GATT roles via [BluetoothMeshService.bleDebug], and the live status dump. The
 * GATT toggles both persist the preference and apply it immediately to the running bearer.
 */
@SingleIn(AppScope::class)
@Inject
internal class DebugRepositoryImpl(
    private val prefs: DebugPreferenceManager,
    private val mesh: BluetoothMeshService,
    private val telemetry: DebugSettingsManager,
) : DebugRepository {

    override fun gattServerEnabled(): Boolean = prefs.getGattServerEnabled()

    override fun setGattServerEnabled(enabled: Boolean) {
        prefs.setGattServerEnabled(enabled)
        if (enabled) mesh.bleDebug.startServer() else mesh.bleDebug.stopServer()
    }

    override fun gattClientEnabled(): Boolean = prefs.getGattClientEnabled()

    override fun setGattClientEnabled(enabled: Boolean) {
        prefs.setGattClientEnabled(enabled)
        if (enabled) mesh.bleDebug.startClient() else mesh.bleDebug.stopClient()
    }

    override fun verboseLoggingEnabled(): Boolean = prefs.getVerboseLogging()

    override fun setVerboseLoggingEnabled(enabled: Boolean) = prefs.setVerboseLogging(enabled)

    override fun packetRelayEnabled(): Boolean = prefs.getPacketRelayEnabled()

    override fun setPacketRelayEnabled(enabled: Boolean) = prefs.setPacketRelayEnabled(enabled)

    override fun seenPacketCapacity(): Int = prefs.getSeenPacketCapacity()

    override fun setSeenPacketCapacity(value: Int) = prefs.setSeenPacketCapacity(value)

    override fun debugStatus(): String = runCatching { mesh.getDebugStatus() }.getOrElse { "unavailable" }

    override fun observePacketLog(): Flow<List<PacketLogEntry>> =
        telemetry.debugMessages.map { messages -> messages.map { it.toEntry() } }

    private fun DebugMessage.toEntry(): PacketLogEntry = PacketLogEntry(
        kind = when (this) {
            is DebugMessage.SystemMessage -> PacketLogKind.SYSTEM
            is DebugMessage.PeerEvent -> PacketLogKind.PEER
            is DebugMessage.PacketEvent -> PacketLogKind.PACKET
            is DebugMessage.RelayEvent -> PacketLogKind.RELAY
        },
        text = content,
        timestampMs = timestamp.toEpochMilliseconds(),
    )
}
