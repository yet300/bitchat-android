package com.app.transport.mesh

/**
 * Narrow BLE-specific debug surface (ISP) for the debug settings sheet. Implemented by
 * [BleBearer]; keeps GATT role controls and address diagnostics available to the debug UI
 * without exposing the raw [BluetoothConnectionManager] outside the BLE stack.
 */
interface BleDebugHandle {
    fun startServer()
    fun stopServer()
    fun startClient()
    fun stopClient()

    /** (deviceAddress, isClient, rssi) snapshot of currently connected BLE devices. */
    fun connectedDeviceEntries(): List<Triple<String, Boolean, Int?>>

    fun localAdapterAddress(): String?
    fun connectToAddress(address: String): Boolean
    fun disconnectAddress(address: String)

    /** Read-only snapshot of the device-address → peerID map. */
    fun addressPeerSnapshot(): Map<String, String>

    /** Human-readable BLE debug summary. */
    fun debugInfo(): String
}
