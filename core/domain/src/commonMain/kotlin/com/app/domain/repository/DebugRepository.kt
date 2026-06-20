package com.app.domain.repository

import com.app.domain.model.PacketLogEntry
import kotlinx.coroutines.flow.Flow

/**
 * Developer/diagnostic controls for the mesh (P24). Re-homes the GATT-role toggles, logging and
 * sync caps that lived in the legacy debug sheet, plus a live human-readable status dump. Values are
 * read synchronously (user-driven settings, not reactive streams); setters persist + apply.
 */
interface DebugRepository {

    fun gattServerEnabled(): Boolean
    /** Persist the preference and start/stop the BLE GATT server immediately. */
    fun setGattServerEnabled(enabled: Boolean)

    fun gattClientEnabled(): Boolean
    /** Persist the preference and start/stop the BLE GATT client immediately. */
    fun setGattClientEnabled(enabled: Boolean)

    fun verboseLoggingEnabled(): Boolean
    fun setVerboseLoggingEnabled(enabled: Boolean)

    fun packetRelayEnabled(): Boolean
    fun setPacketRelayEnabled(enabled: Boolean)

    /** Seen-packet de-dup capacity (drop-oldest horizon). */
    fun seenPacketCapacity(): Int
    fun setSeenPacketCapacity(value: Int)

    /** Live human-readable BLE/mesh debug summary. */
    fun debugStatus(): String

    /** Live, newest-last packet/event log from the mesh traffic recorder. */
    fun observePacketLog(): Flow<List<PacketLogEntry>>
}
