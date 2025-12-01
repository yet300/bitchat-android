package com.bitchat.android.feature.chat.sheet.debug.integration

import com.bitchat.android.feature.chat.sheet.debug.DebugComponent
import com.bitchat.android.feature.chat.sheet.debug.store.DebugStore

internal val stateToModel: (DebugStore.State) -> DebugComponent.Model = { state ->
    DebugComponent.Model(
        verboseLoggingEnabled = state.verboseLoggingEnabled,
        gattServerEnabled = state.gattServerEnabled,
        gattClientEnabled = state.gattClientEnabled,
        packetRelayEnabled = state.packetRelayEnabled,
        maxConnectionsOverall = state.maxConnectionsOverall,
        maxServerConnections = state.maxServerConnections,
        maxClientConnections = state.maxClientConnections,
        debugMessages = state.debugMessages,
        scanResults = state.scanResults,
        connectedDevices = state.connectedDevices,
        relayStats = state.relayStats,
        seenPacketCapacity = state.seenPacketCapacity,
        gcsMaxBytes = state.gcsMaxBytes,
        gcsFprPercent = state.gcsFprPercent,
        localAdapterAddress = state.localAdapterAddress
    )
}
