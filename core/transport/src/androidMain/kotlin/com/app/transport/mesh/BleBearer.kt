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
import kotlinx.coroutines.flow.update

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
class BleBearer internal constructor(
    private val context: Context,
    myPeerID: String,
    private val debugSettingsManager: MeshTelemetry,
    private val fragmentManager: FragmentManager?,
    private val transferProgressManager: TransferProgressManager,
    // Test seam ([BluetoothConnectionManager] is internal, hence the internal primary
    // constructor): lifecycle tests substitute a mock BLE stack to prove that late GATT
    // callbacks on a pre-reset() manager cannot leak into the new generation. Production
    // (the public constructor below) always uses the real stack.
    private val connectionManagerFactory: (myPeerID: String) -> BluetoothConnectionManager,
) : MeshBearer, BleDebugHandle {

    constructor(
        context: Context,
        myPeerID: String,
        debugSettingsManager: MeshTelemetry,
        fragmentManager: FragmentManager? = null,
        transferProgressManager: TransferProgressManager,
    ) : this(
        context, myPeerID, debugSettingsManager, fragmentManager, transferProgressManager,
        { pid ->
            BluetoothConnectionManager(context, pid, debugSettingsManager, fragmentManager, transferProgressManager)
        },
    )

    @Volatile
    private var myPeerID: String = myPeerID

    @Volatile
    private var nicknameResolver: ((String) -> String?)? = null

    // Survives reset(): the new BLE stack's PowerManager must inherit the current state.
    @Volatile
    private var meshServiceActive: Boolean = false

    // -----------------------------------------------------------------
    // Underlying BLE stack
    // -----------------------------------------------------------------

    /**
     * The raw [BluetoothConnectionManager] — an internal detail of the BLE stack.
     * Replaced in place by [reset]; the BleBearer object itself keeps its graph
     * identity (it lives inside the multibound Set<MeshBearer>).
     */
    private var connectionManager = connectionManagerFactory(myPeerID)

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
     * Ownership check: ignores pseudo-addresses owned by other bearers (e.g. the
     * `aware:` namespace of [WifiAwareBearer]); everything else is a BLE MAC.
     */
    override fun bindPeer(peerID: String, linkAddress: String) {
        if (linkAddress.startsWith(WifiAwareBearer.LINK_PREFIX)) return
        connectionManager.addressPeerMap[linkAddress] = peerID
        val isInbound = connectionManager.isClientConnection(linkAddress) == false
        _neighbors.update { links ->
            links.filterNot { it.deviceAddress == linkAddress }.toSet() +
                PeerLink(peerID, linkAddress, isInbound = isInbound)
        }
    }

    private fun notifyPeerDisconnected(deviceAddress: String) {
        _neighbors.update { links -> links.filterNot { it.deviceAddress == deviceAddress }.toSet() }
    }

    init {
        wireConnectionManager()
    }

    private fun wireConnectionManager() {
        // Capture THIS generation's manager: BLE callbacks are asynchronous and may fire
        // after reset() swapped the field — they must never touch the new stack's state.
        val cm = connectionManager
        cm.delegate = object : BluetoothConnectionManagerDelegate {
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
                    val peer = cm.addressPeerMap[addr]
                    val nick = peer?.let { nicknameResolver?.invoke(it) } ?: "unknown"
                    val inbound = cm.isClientConnection(addr) == false
                    debugSettingsManager.logPeerConnection(peer ?: "unknown", nick, addr, inbound)
                } catch (_: Exception) { }
                _events.tryEmit(BearerEvent.LinkConnected(device.address))
            }

            override fun onDeviceDisconnected(device: BluetoothDevice) {
                val addr = device.address
                val peer = cm.addressPeerMap[addr]
                // ConnectionTracker has already removed the address mapping; be defensive either way
                cm.addressPeerMap.remove(addr)
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
                _neighbors.update { links ->
                    links.map { link ->
                        if (link.deviceAddress == deviceAddress) link.copy(rssi = rssi) else link
                    }.toSet()
                }
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
        val old = connectionManager
        try { old.stopServices() } catch (_: Exception) { }
        // Detach the old stack's delegate: late asynchronous GATT callbacks must neither
        // emit stale packets into the new generation's flow nor evict fresh address
        // bindings (audit finding A6).
        old.delegate = null
        this.myPeerID = myPeerID
        _neighbors.value = emptySet()
        connectionManager = connectionManagerFactory(myPeerID)
        wireConnectionManager()
        nicknameResolver?.let { connectionManager.setNicknameResolver(it) }
        connectionManager.setMeshServiceActive(meshServiceActive)
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

    /**
     * Signals that the mesh foreground service is active so the BLE power manager treats the
     * process as foreground (keeps discovery on the BALANCED duty cycle when the screen is off).
     */
    fun setMeshServiceActive(active: Boolean) {
        meshServiceActive = active
        connectionManager.setMeshServiceActive(active)
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
