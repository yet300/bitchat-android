package com.yet.bitmessage.feature.debug.integration

import com.yet.bitmessage.feature.debug.DebugComponent
import com.yet.bitmessage.feature.debug.store.DebugStore

internal val stateToModel: (DebugStore.State) -> DebugComponent.Model =  { state ->
    DebugComponent.Model(
        gattServerEnabled = state.gattServerEnabled,
        gattClientEnabled = state.gattClientEnabled,
        verboseLogging = state.verboseLogging,
        packetRelayEnabled = state.packetRelayEnabled,
        seenPacketCapacity = state.seenPacketCapacity,
        status = state.status,
        packetLog = state.packetLog,
        topology = state.topology,
        isPinging = state.isPinging,
        pingResult = state.pingResult,
    )
}