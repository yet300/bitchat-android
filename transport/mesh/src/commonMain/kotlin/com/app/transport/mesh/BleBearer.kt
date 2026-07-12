package com.app.transport.mesh

import com.app.transport.MeshTelemetry
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/**
 * Shared BLE implementation of [MeshBearer]: a single commonMain facade over a platform
 * [BearerTransport] (Android BluetoothConnectionManager / Apple CoreBluetoothConnectionManager).
 *
 * Adapts the transport's address-based callbacks to the bearer contract:
 *   - [incoming] is a hot [Flow] of every packet received from the radio.
 *   - [events] carries link connect / disconnect / RSSI changes — no platform types leak out; links
 *     are opaque addresses.
 *   - [neighbors] is a [StateFlow] of peers bound via [bindPeer].
 *
 * BLE-specific debug controls are exposed through the narrow [BleDebugHandle]. Fragmentation,
 * padding and (de)serialization live in the transport/commonMain, identical across platforms.
 */
class BleBearer(
    myPeerID: String,
    private val debugSettingsManager: MeshTelemetry,
    // Rebuilds the radio stack for [reset] (panic / new identity); the bearer keeps its graph
    // identity (it lives inside the multibound Set<MeshBearer>).
    private val connectionManagerFactory: (myPeerID: String) -> BearerTransport,
    // Ownership predicate for [bindPeer]: returns false for link addresses owned by another bearer
    // (e.g. the `aware:` namespace of the Wi-Fi Aware bearer). Defaults to "owns everything".
    private val ownsLinkAddress: (String) -> Boolean = { true },
) : MeshBearer, BleDebugHandle {

    private companion object {
        // Headroom over the old 64-slot SharedFlow buffer: absorbs a handshake burst on entry
        // to a dense zone before DROP_OLDEST starts shedding the stalest frames.
        const val INCOMING_BUFFER_CAPACITY = 256
    }

    private var myPeerID: String = myPeerID
    private var nicknameResolver: ((String) -> String?)? = null

    // Survives reset(): the new radio stack must inherit the current foreground state.
    private var meshServiceActive: Boolean = false
    private var appIsActive: Boolean = true

    /**
     * The platform radio stack. Replaced in place by [reset]; the BleBearer object keeps its graph
     * identity.
     */
    private var connectionManager: BearerTransport = connectionManagerFactory(myPeerID)

    override val id: BearerId = BearerId.BLE

    // Bounded ingress buffer. Under load the single downstream consumer
    // (MeshCoordinator → PacketProcessor) can stall — e.g. a burst of Noise handshakes on
    // entry to a dense zone — so instead of the old fire-and-forget SharedFlow.tryEmit (which
    // silently discarded the *newest* frame once its 64-slot buffer filled), we DROP_OLDEST:
    // stale frames have usually already been relayed by neighbors, whereas the newest frame may
    // be a handshake response we cannot afford to lose. Every dropped frame is counted in
    // telemetry so the loss is no longer silent. Single-consumer (verified: only the merged
    // MeshNetwork.incoming collects this), so a Channel is safe.
    private val _incoming = Channel<RoutedPacket>(
        capacity = INCOMING_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { debugSettingsManager.onIncomingDropped(id) },
    )
    override val incoming: Flow<RoutedPacket> = _incoming.receiveAsFlow()

    private val _neighbors = MutableStateFlow<Set<PeerLink>>(emptySet())
    override val neighbors: StateFlow<Set<PeerLink>> = _neighbors.asStateFlow()

    private val _events = MutableSharedFlow<BearerEvent>(extraBufferCapacity = 64)
    override val events: Flow<BearerEvent> = _events.asSharedFlow()

    /**
     * Engine-driven binding of a link address to a logical peerID (announce received with max TTL ⇒
     * direct neighbor). The bearer owns the address↔peer map; addresses owned by another bearer
     * (per [ownsLinkAddress]) are ignored.
     */
    override fun bindPeer(peerID: String, linkAddress: String) {
        if (!ownsLinkAddress(linkAddress)) return
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
        // Capture THIS generation's transport: radio callbacks are asynchronous and may fire after
        // reset() swapped the field — they must never touch the new stack's state.
        val cm = connectionManager
        cm.delegate = object : BearerTransportDelegate {
            override fun onPacketReceived(packet: BitchatPacket, peerID: String, deviceAddress: String?) {
                try {
                    debugSettingsManager.logIncoming(
                        packet = packet,
                        fromPeerID = peerID,
                        fromNickname = null,
                        fromDeviceAddress = deviceAddress,
                        myPeerID = myPeerID,
                    )
                } catch (_: Exception) {}
                _incoming.trySend(RoutedPacket(packet, peerID, deviceAddress))
            }

            override fun onDeviceConnected(deviceAddress: String) {
                try {
                    val peer = cm.addressPeerMap[deviceAddress]
                    val nick = peer?.let { nicknameResolver?.invoke(it) } ?: "unknown"
                    val inbound = cm.isClientConnection(deviceAddress) == false
                    debugSettingsManager.logPeerConnection(peer ?: "unknown", nick, deviceAddress, inbound)
                } catch (_: Exception) {}
                _events.tryEmit(BearerEvent.LinkConnected(deviceAddress))
            }

            override fun onDeviceDisconnected(deviceAddress: String) {
                val peer = cm.addressPeerMap[deviceAddress]
                cm.addressPeerMap.remove(deviceAddress)
                notifyPeerDisconnected(deviceAddress)
                if (peer != null) {
                    try {
                        val nick = nicknameResolver?.invoke(peer) ?: "unknown"
                        debugSettingsManager.logPeerDisconnection(peer, nick, deviceAddress)
                    } catch (_: Exception) {}
                }
                _events.tryEmit(BearerEvent.LinkDisconnected(deviceAddress))
            }

            override fun onRSSIUpdated(deviceAddress: String, rssi: Int) {
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
     * Rebuild the radio stack for a new peer identity (panic reset) or after a terminal stop. The
     * BleBearer object identity is preserved — the graph (incl. the multibound Set<MeshBearer>)
     * keeps serving this instance.
     */
    fun reset(myPeerID: String) {
        val old = connectionManager
        try { old.stopServices() } catch (_: Exception) {}
        // Detach the old stack's delegate: late asynchronous callbacks must neither emit stale
        // packets into the new generation's flow nor evict fresh address bindings.
        old.delegate = null
        this.myPeerID = myPeerID
        _neighbors.value = emptySet()
        connectionManager = connectionManagerFactory(myPeerID)
        wireConnectionManager()
        nicknameResolver?.let { connectionManager.setNicknameResolver(it) }
        connectionManager.setMeshServiceActive(meshServiceActive)
        connectionManager.setAppIsActive(appIsActive)
    }

    override fun start(): Boolean = connectionManager.startServices()
    override fun stop() = connectionManager.stopServices()
    override fun broadcast(packet: RoutedPacket) = connectionManager.broadcastPacket(packet)
    override fun sendToPeer(peerID: String, packet: RoutedPacket): Boolean =
        connectionManager.sendToPeer(peerID, packet)
    override fun cancelTransfer(transferId: String): Boolean =
        connectionManager.cancelTransfer(transferId)

    /** Injects a nickname resolver so BLE debug logs show human names. */
    fun setNicknameResolver(resolver: (String) -> String?) {
        nicknameResolver = resolver
        connectionManager.setNicknameResolver(resolver)
    }

    /** Signals foreground-service state so the radio's power manager treats the process as foreground. */
    fun setMeshServiceActive(active: Boolean) {
        meshServiceActive = active
        connectionManager.setMeshServiceActive(active)
    }

    /** Signals process foreground activity to the platform scan-duty policy. */
    fun setAppIsActive(active: Boolean) {
        appIsActive = active
        connectionManager.setAppIsActive(active)
    }

    // BleDebugHandle (debug sheet only)
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
