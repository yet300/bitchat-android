package com.app.transport.mesh

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.app.transport.MeshTelemetry
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BLE (Bluetooth Low Energy) implementation of [MeshBearer].
 *
 * Wraps [BluetoothConnectionManager] and adapts its callback-based delegate API to the
 * [MeshBearer] interface:
 *
 *   - [incoming] is a hot [Flow] of every packet received from the BLE stack.
 *   - [events] carries link connect / disconnect / RSSI changes — no
 *     android.bluetooth types leak out of the bearer.
 *   - [neighbors] is a [StateFlow] of peers bound via [bindPeer].
 *
 * BLE-specific debug controls for the debug sheet are exposed through the narrow
 * [BleDebugHandle] interface.
 */
class BleBearer(
    private val context: Context,
    myPeerID: String,
    private val debugSettingsManager: MeshTelemetry,
    private val fragmentManager: FragmentManager? = null,
    private val transferProgressManager: TransferProgressManager,
) : MeshBearer, BleDebugHandle {

    @Volatile
    private var myPeerID: String = myPeerID

    @Volatile
    private var nicknameResolver: ((String) -> String?)? = null

    // -----------------------------------------------------------------
    // Underlying BLE stack
    // -----------------------------------------------------------------

    /**
     * The raw [BluetoothConnectionManager] — an internal detail of the BLE stack.
     * Replaced in place by [reset]; the BleBearer object itself keeps its graph
     * identity (it lives inside the multibound Set<MeshBearer>).
     */
    private var connectionManager = BluetoothConnectionManager(
        context, myPeerID, debugSettingsManager, fragmentManager, transferProgressManager,
    )

    // -----------------------------------------------------------------
    // MeshBearer identity
    // -----------------------------------------------------------------

    override val id: BearerId = BearerId.BLE

    // -----------------------------------------------------------------
    // Incoming packet flow
    // -----------------------------------------------------------------

    private val _incoming = MutableSharedFlow<RoutedPacket>(extraBufferCapacity = 64)

    /** Hot stream of all packets received by the BLE stack. */
    override val incoming: Flow<RoutedPacket> = _incoming.asSharedFlow()

    // -----------------------------------------------------------------
    // Neighbors (connected peers)
    // -----------------------------------------------------------------

    private val _neighbors = MutableStateFlow<Set<PeerLink>>(emptySet())

    /** Live set of BLE peers bound via [bindPeer]. */
    override val neighbors: StateFlow<Set<PeerLink>> = _neighbors.asStateFlow()

    // -----------------------------------------------------------------
    // Link events
    // -----------------------------------------------------------------

    private val _events = MutableSharedFlow<BearerEvent>(extraBufferCapacity = 64)

    /** Link-level connect / disconnect / RSSI events (no platform types leak out). */
    override val events: Flow<BearerEvent> = _events.asSharedFlow()

    /**
     * Engine-driven binding of a BLE device address to a logical peerID (announce
     * received with max TTL ⇒ direct neighbor). The bearer owns the address↔peer map.
     *
     * Binds unconditionally: BLE packets only arrive over BLE links, so any address the
     * engine saw on [incoming] belongs to this stack. Once a second link-bearing bearer
     * exists, an ownership check (e.g. tracker lookup) must be added here.
     */
    override fun bindPeer(peerID: String, linkAddress: String) {
        connectionManager.addressPeerMap[linkAddress] = peerID
        val isInbound = connectionManager.isClientConnection(linkAddress) == false
        _neighbors.value = _neighbors.value
            .filterNot { it.deviceAddress == linkAddress }
            .toSet() + PeerLink(peerID, linkAddress, isInbound = isInbound)
    }

    private fun notifyPeerDisconnected(deviceAddress: String) {
        _neighbors.value = _neighbors.value.filterNot { it.deviceAddress == deviceAddress }.toSet()
    }

    init {
        wireConnectionManager()
    }

    private fun wireConnectionManager() {
        connectionManager.delegate = object : BluetoothConnectionManagerDelegate {
            override fun onPacketReceived(
                packet: BitchatPacket,
                peerID: String,
                device: BluetoothDevice?,
            ) {
                // Debug telemetry lives with the data it describes (do not double-count elsewhere)
                try {
                    debugSettingsManager.logIncoming(
                        packet = packet,
                        fromPeerID = peerID,
                        fromNickname = null,
                        fromDeviceAddress = device?.address,
                        myPeerID = myPeerID,
                    )
                } catch (_: Exception) { }
                // Emit to the bearer flow (non-blocking; drops if buffer full)
                _incoming.tryEmit(RoutedPacket(packet, peerID, device?.address))
            }

            override fun onDeviceConnected(device: BluetoothDevice) {
                try {
                    val addr = device.address
                    val peer = connectionManager.addressPeerMap[addr]
                    val nick = peer?.let { nicknameResolver?.invoke(it) } ?: "unknown"
                    val inbound = connectionManager.isClientConnection(addr) == false
                    debugSettingsManager.logPeerConnection(peer ?: "unknown", nick, addr, inbound)
                } catch (_: Exception) { }
                _events.tryEmit(BearerEvent.LinkConnected(device.address))
            }

            override fun onDeviceDisconnected(device: BluetoothDevice) {
                val addr = device.address
                val peer = connectionManager.addressPeerMap[addr]
                // ConnectionTracker has already removed the address mapping; be defensive either way
                connectionManager.addressPeerMap.remove(addr)
                notifyPeerDisconnected(addr)
                if (peer != null) {
                    try {
                        val nick = nicknameResolver?.invoke(peer) ?: "unknown"
                        debugSettingsManager.logPeerDisconnection(peer, nick, addr)
                    } catch (_: Exception) { }
                }
                _events.tryEmit(BearerEvent.LinkDisconnected(addr))
            }

            override fun onRSSIUpdated(deviceAddress: String, rssi: Int) {
                // Update RSSI on the matching PeerLink if it exists
                val updated = _neighbors.value.map { link ->
                    if (link.deviceAddress == deviceAddress) link.copy(rssi = rssi) else link
                }.toSet()
                _neighbors.value = updated
                _events.tryEmit(BearerEvent.RssiChanged(deviceAddress, rssi))
            }
        }
    }

    /**
     * Rebuild the internal BLE stack for a new peer identity (panic reset) or after a
     * terminal stop. The BleBearer object identity is preserved — the graph (including
     * the multibound Set<MeshBearer>) keeps serving this instance.
     */
    fun reset(myPeerID: String) {
        try { connectionManager.stopServices() } catch (_: Exception) { }
        this.myPeerID = myPeerID
        _neighbors.value = emptySet()
        connectionManager = BluetoothConnectionManager(
            context, myPeerID, debugSettingsManager, fragmentManager, transferProgressManager,
        )
        wireConnectionManager()
        nicknameResolver?.let { connectionManager.setNicknameResolver(it) }
    }

    // -----------------------------------------------------------------
    // MeshBearer: send / lifecycle
    // -----------------------------------------------------------------

    override fun start(): Boolean = connectionManager.startServices()
    override fun stop() = connectionManager.stopServices()
    override fun broadcast(packet: RoutedPacket) = connectionManager.broadcastPacket(packet)
    override fun sendToPeer(peerID: String, packet: RoutedPacket): Boolean =
        connectionManager.sendToPeer(peerID, packet)
    override fun cancelTransfer(transferId: String): Boolean =
        connectionManager.cancelTransfer(transferId)

    // -----------------------------------------------------------------
    // BLE-specific extras
    // -----------------------------------------------------------------

    /** Injects a nickname resolver so BLE debug logs show human names. */
    fun setNicknameResolver(resolver: (String) -> String?) {
        nicknameResolver = resolver
        connectionManager.setNicknameResolver(resolver)
    }

    // -----------------------------------------------------------------
    // BleDebugHandle (debug sheet only)
    // -----------------------------------------------------------------

    override fun startServer() = connectionManager.startServer()
    override fun stopServer() = connectionManager.stopServer()
    override fun startClient() = connectionManager.startClient()
    override fun stopClient() = connectionManager.stopClient()
    override fun connectedDeviceEntries(): List<Triple<String, Boolean, Int?>> =
        connectionManager.getConnectedDeviceEntries()
    override fun localAdapterAddress(): String? = connectionManager.getLocalAdapterAddress()
    override fun connectToAddress(address: String): Boolean = connectionManager.connectToAddress(address)
    override fun disconnectAddress(address: String) = connectionManager.disconnectAddress(address)
    override fun addressPeerSnapshot(): Map<String, String> = connectionManager.addressPeerMap.toMap()
    override fun debugInfo(): String = connectionManager.getDebugInfo()
}
