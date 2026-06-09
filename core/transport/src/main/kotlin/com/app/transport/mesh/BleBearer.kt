package com.app.transport.mesh

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.app.transport.debug.DebugSettingsManager
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
 * Wraps [BluetoothConnectionManager] and adapts its callback-based
 * [BluetoothConnectionManagerDelegate] API to the [MeshBearer] interface:
 *
 *   - [incoming] is a hot [Flow] of every packet received from the BLE stack.
 *     Future [MeshNetwork] consumers merge this with other bearers' flows.
 *   - [neighbors] is a [StateFlow] updated on every BLE connect / disconnect.
 *
 * BLE-specific operations that are not part of the generic [MeshBearer]
 * contract (GATT server/client control, address-peer mapping, nickname
 * resolver, debug info) are exposed as direct members for the legacy
 * [BluetoothMeshService] call sites until Phase C dissolves them.
 */
class BleBearer(
    context: Context,
    myPeerID: String,
    debugSettingsManager: DebugSettingsManager,
    fragmentManager: FragmentManager? = null,
    transferProgressManager: TransferProgressManager,
) : MeshBearer {

    // -----------------------------------------------------------------
    // Underlying BLE stack
    // -----------------------------------------------------------------

    /**
     * The raw [BluetoothConnectionManager] — kept accessible so that
     * [BluetoothMeshService] can still expose it to [DebugSettingsSheet]
     * until that UI layer is migrated (Phase C).
     */
    val connectionManager = BluetoothConnectionManager(
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

    /**
     * Hot stream of all packets received by the BLE stack.
     *
     * [BluetoothMeshService] currently processes packets through the legacy
     * [externalDelegate] path.  The flow exists so that [MeshNetwork] can
     * merge it with other bearers once BMS is refactored (Phase C).
     */
    override val incoming: Flow<RoutedPacket> = _incoming.asSharedFlow()

    // -----------------------------------------------------------------
    // Neighbors (connected peers)
    // -----------------------------------------------------------------

    private val _neighbors = MutableStateFlow<Set<PeerLink>>(emptySet())

    /** Live set of BLE peers mapped from the [addressPeerMap]. */
    override val neighbors: StateFlow<Set<PeerLink>> = _neighbors.asStateFlow()

    /**
     * Called by [BluetoothMeshService] when it binds a device address to a
     * logical peerID (from an announce packet with max TTL).
     * Keeps [neighbors] in sync so [MeshNetwork] can route unicasts.
     */
    fun notifyPeerMapped(peerID: String, deviceAddress: String, isInbound: Boolean) {
        _neighbors.value = _neighbors.value
            .filterNot { it.deviceAddress == deviceAddress }
            .toSet() + PeerLink(peerID, deviceAddress, isInbound)
    }

    /** Called by [BluetoothMeshService] when a BLE device disconnects. */
    fun notifyPeerDisconnected(deviceAddress: String) {
        _neighbors.value = _neighbors.value.filterNot { it.deviceAddress == deviceAddress }.toSet()
    }

    // -----------------------------------------------------------------
    // Delegate wiring
    //
    // BleBearer owns the connectionManager delegate so that it can feed
    // the [incoming] flow.  [BluetoothMeshService] registers its own
    // higher-level handler as [externalDelegate]; BleBearer forwards all
    // callbacks to it after emitting to the flow.
    // -----------------------------------------------------------------

    /**
     * Higher-level delegate set by [BluetoothMeshService].
     * Receives the same callbacks as before, but now also allows [incoming]
     * to be consumed independently by [MeshNetwork].
     */
    var externalDelegate: BluetoothConnectionManagerDelegate? = null

    init {
        connectionManager.delegate = object : BluetoothConnectionManagerDelegate {
            override fun onPacketReceived(
                packet: BitchatPacket,
                peerID: String,
                device: BluetoothDevice?,
            ) {
                // Emit to the bearer flow first (non-blocking; drops if buffer full)
                _incoming.tryEmit(RoutedPacket(packet, peerID, device?.address))
                // Then forward to the legacy BMS handler
                externalDelegate?.onPacketReceived(packet, peerID, device)
            }

            override fun onDeviceConnected(device: BluetoothDevice) {
                externalDelegate?.onDeviceConnected(device)
            }

            override fun onDeviceDisconnected(device: BluetoothDevice) {
                notifyPeerDisconnected(device.address)
                externalDelegate?.onDeviceDisconnected(device)
            }

            override fun onRSSIUpdated(deviceAddress: String, rssi: Int) {
                // Update RSSI on the matching PeerLink if it exists
                val updated = _neighbors.value.map { link ->
                    if (link.deviceAddress == deviceAddress) link.copy(rssi = rssi) else link
                }.toSet()
                _neighbors.value = updated
                externalDelegate?.onRSSIUpdated(deviceAddress, rssi)
            }
        }
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
    // BLE-specific pass-throughs
    //
    // Used by [BluetoothMeshService] call sites that are not yet migrated
    // to the generic [MeshBearer] interface.
    // -----------------------------------------------------------------

    /** Device-address → peerID map maintained by [BluetoothConnectionTracker]. */
    val addressPeerMap get() = connectionManager.addressPeerMap

    /**
     * Send a raw [BitchatPacket] to a peer by peerID (used by
     * [GossipSyncManager] delegate which operates at the packet level).
     */
    fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean =
        connectionManager.sendPacketToPeer(peerID, packet)

    /** Whether [addr] is a client-side (outbound) BLE connection. */
    fun isClientConnection(addr: String): Boolean? = connectionManager.isClientConnection(addr)

    /** Injects a nickname resolver so BLE debug logs show human names. */
    fun setNicknameResolver(resolver: (String) -> String?) =
        connectionManager.setNicknameResolver(resolver)

    /** Whether this instance can be safely reused after [stop]. */
    fun isReusable(): Boolean = connectionManager.isReusable()

    /** Human-readable BLE debug summary delegated to [BluetoothConnectionManager]. */
    fun getDebugInfo(): String = connectionManager.getDebugInfo()
}
