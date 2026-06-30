package com.app.transport.mesh

import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket

/**
 * Callbacks from a platform [BearerTransport] up to the shared [BleBearer]. Free of platform types:
 * a link is identified only by its opaque, bearer-private address (Android BLE MAC, iOS NSUUID
 * string, …) — never parsed.
 */
interface BearerTransportDelegate {
    fun onPacketReceived(packet: BitchatPacket, peerID: String, deviceAddress: String?)
    fun onDeviceConnected(deviceAddress: String)
    fun onDeviceDisconnected(deviceAddress: String)
    fun onRSSIUpdated(deviceAddress: String, rssi: Int)
}

/**
 * Platform BLE stack abstraction behind the shared [BleBearer].
 *
 * Lets the bearer facade (incoming/neighbors/events flows, peer binding, panic reset, debug surface)
 * live once in commonMain while each platform supplies only the radio plumbing:
 *   - Android: BluetoothConnectionManager (GATT server/client managers + packet broadcaster).
 *   - Apple:   CoreBluetoothConnectionManager (CBPeripheralManager + CBCentralManager).
 *
 * Addresses are opaque strings throughout; no android.bluetooth / CoreBluetooth type crosses this
 * boundary. The role/diagnostic methods back the debug-only [BleDebugHandle]; platforms without a
 * meaningful implementation (iOS) may return empty/no-op values.
 */
interface BearerTransport {

    /** External delegate the bearer installs to receive packets and link events. */
    var delegate: BearerTransportDelegate?

    /** linkAddress -> logical peerID map, owned by the bearer (mutated via [BleBearer.bindPeer]). */
    val addressPeerMap: MutableMap<String, String>

    fun startServices(): Boolean
    fun stopServices()

    fun broadcastPacket(routed: RoutedPacket)
    fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean
    fun cancelTransfer(transferId: String): Boolean

    /** True if [address] is a client (outbound) connection, false if server (inbound), null if unknown. */
    fun isClientConnection(address: String): Boolean?

    fun setNicknameResolver(resolver: (String) -> String?)
    fun setMeshServiceActive(active: Boolean)

    // Debug-only role controls / diagnostics (BleDebugHandle).
    fun startServer()
    fun stopServer()
    fun startClient()
    fun stopClient()
    fun getConnectedDeviceEntries(): List<Triple<String, Boolean, Int?>>
    fun getLocalAdapterAddress(): String?
    fun connectToAddress(address: String): Boolean
    fun disconnectAddress(address: String)
    fun getDebugInfo(): String
}
