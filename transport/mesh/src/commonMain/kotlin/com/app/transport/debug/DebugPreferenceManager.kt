package com.app.transport.debug

/**
 * Debug-option persistence over the [DebugConfigStore] port. Keeps the [DebugSettingsManager]
 * free of any storage concern; defaults live here at the call sites, not in the store.
 */
class DebugPreferenceManager(
    private val store: DebugConfigStore,
) {

    fun getVerboseLogging(default: Boolean = false): Boolean = store.getVerboseLogging(default)

    fun setVerboseLogging(value: Boolean) = store.setVerboseLogging(value)

    fun getGattServerEnabled(default: Boolean = true): Boolean = store.getGattServerEnabled(default)

    fun setGattServerEnabled(value: Boolean) = store.setGattServerEnabled(value)

    fun getGattClientEnabled(default: Boolean = true): Boolean = store.getGattClientEnabled(default)

    fun setGattClientEnabled(value: Boolean) = store.setGattClientEnabled(value)

    fun getPacketRelayEnabled(default: Boolean = true): Boolean = store.getPacketRelayEnabled(default)

    fun setPacketRelayEnabled(value: Boolean) = store.setPacketRelayEnabled(value)

    // Optional connection limits (0 or missing => use defaults)
    fun getMaxConnectionsOverall(default: Int = 8): Int = store.getMaxConnectionsOverall(default)

    fun setMaxConnectionsOverall(value: Int) = store.setMaxConnectionsOverall(value)

    fun getMaxConnectionsServer(default: Int = 8): Int = store.getMaxConnectionsServer(default)

    fun setMaxConnectionsServer(value: Int) = store.setMaxConnectionsServer(value)

    fun getMaxConnectionsClient(default: Int = 8): Int = store.getMaxConnectionsClient(default)

    fun setMaxConnectionsClient(value: Int) = store.setMaxConnectionsClient(value)

    // Sync/GCS settings
    fun getSeenPacketCapacity(default: Int = 500): Int = store.getSeenPacketCapacity(default)

    fun setSeenPacketCapacity(value: Int) = store.setSeenPacketCapacity(value)

    fun getGcsMaxFilterBytes(default: Int = 400): Int = store.getGcsMaxFilterBytes(default)

    fun setGcsMaxFilterBytes(value: Int) = store.setGcsMaxFilterBytes(value)

    fun getGcsFprPercent(default: Double = 1.0): Double = store.getGcsFprPercent(default)

    fun setGcsFprPercent(value: Double) = store.setGcsFprPercent(value)
}
