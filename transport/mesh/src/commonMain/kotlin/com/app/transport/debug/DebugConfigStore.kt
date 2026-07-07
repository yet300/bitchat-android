package com.app.transport.debug

/**
 * Persistence port for the mesh debug options. The transport SDK owns no storage: the interface
 * lives here next to its consumer ([DebugPreferenceManager]) and the app supplies the
 * implementation (bitMessage backs it with the :core:data settings store).
 *
 * Getters take the caller's default so the toggle semantics (GATT/relay default-on, verbose
 * default-off) stay defined at the call site, exactly as before the port existed.
 */
interface DebugConfigStore {

    fun getVerboseLogging(default: Boolean): Boolean

    fun setVerboseLogging(value: Boolean)

    fun getGattServerEnabled(default: Boolean): Boolean

    fun setGattServerEnabled(value: Boolean)

    fun getGattClientEnabled(default: Boolean): Boolean

    fun setGattClientEnabled(value: Boolean)

    fun getPacketRelayEnabled(default: Boolean): Boolean

    fun setPacketRelayEnabled(value: Boolean)

    fun getMaxConnectionsOverall(default: Int): Int

    fun setMaxConnectionsOverall(value: Int)

    fun getMaxConnectionsServer(default: Int): Int

    fun setMaxConnectionsServer(value: Int)

    fun getMaxConnectionsClient(default: Int): Int

    fun setMaxConnectionsClient(value: Int)

    fun getSeenPacketCapacity(default: Int): Int

    fun setSeenPacketCapacity(value: Int)

    fun getGcsMaxFilterBytes(default: Int): Int

    fun setGcsMaxFilterBytes(value: Int)

    fun getGcsFprPercent(default: Double): Double

    fun setGcsFprPercent(value: Double)
}
