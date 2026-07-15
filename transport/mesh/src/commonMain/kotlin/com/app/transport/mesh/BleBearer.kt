@file:OptIn(ExperimentalTime::class)

package com.app.transport.mesh

import com.app.common.encoding.toHexString
import com.app.common.utils.Log
import com.app.transport.MeshConstants
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
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

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
    private val radioConfig: BleRadioConfig = BleRadioConfig(),
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : MeshBearer, BleDebugHandle {

    private companion object {
        private const val TAG = "BleBearer"
        // Headroom over the old 64-slot SharedFlow buffer: absorbs a handshake burst on entry
        // to a dense zone before DROP_OLDEST starts shedding the stalest frames.
        const val INCOMING_BUFFER_CAPACITY = 256
    }

    private var myPeerID: String = myPeerID
    private var nicknameResolver: ((String) -> String?)? = null

    /**
     * Solicitation gate for inbound RSR (Flags.IS_RSR). Wired by [MeshCoordinator] to
     * [com.app.transport.sync.RequestSyncManager.isValidResponse]. Default rejects all RSR
     * (safe until the mesh engine attaches a real registry).
     */
    var isValidSyncResponse: (peerID: String) -> Boolean = { false }

    // Survives reset(): the new radio stack must inherit the current foreground state.
    private var meshServiceActive: Boolean = false
    private var appIsActive: Boolean = true

    // iOS BLEService.lastRedundantLinkRetirementAt — one retirement pass per peer per cooldown.
    private val lastRedundantLinkRetirementAt = mutableMapOf<String, Long>()

    // Short-lived per-packet ingress memory (iOS BLEIngressLinkRegistry).
    private val ingressRegistry = BleIngressLinkRegistry()

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
     *
     * After binding, retires redundant central-role links to the same peer (iOS
     * [BleRedundantLinkPolicy] parity) so restore/reconnect cannot multiply airtime.
     */
    override fun bindPeer(peerID: String, linkAddress: String) {
        if (!ownsLinkAddress(linkAddress)) return
        connectionManager.addressPeerMap[linkAddress] = peerID
        val isInbound = connectionManager.isClientConnection(linkAddress) == false
        _neighbors.update { links ->
            links.filterNot { it.deviceAddress == linkAddress }.toSet() +
                PeerLink(peerID, linkAddress, isInbound = isInbound)
        }
        retireRedundantClientLinks(peerID = peerID, ingressAddress = linkAddress)
        // Peer identity on a live link — re-attempt spooled directed traffic (iOS post-announce flush).
        try { connectionManager.flushDirectedSpool() } catch (_: Exception) {}
    }

    private fun notifyPeerDisconnected(deviceAddress: String) {
        _neighbors.update { links -> links.filterNot { it.deviceAddress == deviceAddress }.toSet() }
    }

    /**
     * Central-role duplicate retirement (iOS `BLEService` after verified direct announce).
     * Only client/outbound links we own are considered; server-role subscriptions stay.
     */
    private fun retireRedundantClientLinks(peerID: String, ingressAddress: String) {
        val now = nowMs()
        val last = lastRedundantLinkRetirementAt[peerID]
        if (last != null && now - last < radioConfig.linkRebindCooldownMs) return

        val snapshots = connectionManager.clientLinkSnapshots()
        if (snapshots.size <= 1) return

        // Reflect the bind we just wrote — platforms read addressPeerMap, but a lagging snapshot
        // peer field is overwritten from the map when present.
        val links = snapshots.map { snap ->
            val boundPeer = connectionManager.addressPeerMap[snap.address] ?: snap.peerID
            snap.copy(peerID = boundPeer).toPolicyLink()
        }

        val keptUUID = BleRedundantLinkPolicy.keptPeripheralUUID(
            ingressPeripheralUUID = ingressAddress,
            mostRecentlyBoundUUID = ingressAddress,
            links = links,
            peerID = peerID,
        ) ?: return

        val retiring = BleRedundantLinkPolicy.peripheralUUIDsToRetire(
            links = links,
            peerID = peerID,
            keeping = keptUUID,
        )
        if (retiring.isEmpty()) return

        lastRedundantLinkRetirementAt[peerID] = now
        // Survivor is the preferred reverse mapping for directed sends.
        connectionManager.addressPeerMap[keptUUID] = peerID

        for (uuid in retiring) {
            // Drop binding before cancel so disconnect callbacks do not treat this as peer leave
            // (the peer is still live on the kept link).
            connectionManager.addressPeerMap.remove(uuid)
            notifyPeerDisconnected(uuid)
            try {
                connectionManager.disconnectAddress(uuid)
            } catch (_: Exception) {
            }
            Log.i(TAG, "Retiring redundant client link ${uuid.take(8)}… for peer ${peerID.take(8)}… (keeping ${keptUUID.take(8)}…)")
        }
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
                val claimedSenderID = packet.senderID.toHexString()
                val boundPeerID = deviceAddress?.let { cm.addressPeerMap[it] }
                val now = nowMs()
                when (
                    val evaluated = BleIngressPacketGuard.evaluate(
                        packet = packet,
                        claimedSenderID = claimedSenderID,
                        boundPeerID = boundPeerID,
                        localPeerID = myPeerID,
                        directAnnounceTTL = MeshConstants.MESSAGE_TTL_HOPS,
                        nowMs = now,
                        maxTimestampSkewMs = radioConfig.ingressMaxTimestampSkewMs,
                        isRSR = packet.isRSR,
                        isValidSyncResponse = isValidSyncResponse,
                    )
                ) {
                    is BleIngressPacketGuard.EvaluateResult.Reject -> {
                        Log.d(
                            TAG,
                            "Dropping ingress packet type ${packet.type}: ${evaluated.rejection}",
                        )
                        return
                    }
                    is BleIngressPacketGuard.EvaluateResult.Accept -> {
                        val context = evaluated.context
                        val linkId = when (cm.isClientConnection(deviceAddress ?: "")) {
                            true -> BleIngressLinkId.Peripheral(deviceAddress ?: "unknown")
                            false -> BleIngressLinkId.Central(deviceAddress ?: "unknown")
                            null -> BleIngressLinkId.Peripheral(deviceAddress ?: "unknown")
                        }
                        if (!ingressRegistry.recordIfNew(
                                packet = packet,
                                link = linkId,
                                peerID = context.receivedFromPeerID,
                                nowMs = now,
                                lifetimeMs = radioConfig.ingressRecordLifetimeMs,
                            )
                        ) {
                            return
                        }
                        try {
                            debugSettingsManager.logIncoming(
                                packet = packet,
                                // Always the claimed logical author; radio hop is in deviceAddress.
                                fromPeerID = claimedSenderID,
                                fromNickname = null,
                                fromDeviceAddress = deviceAddress,
                                myPeerID = myPeerID,
                            )
                        } catch (_: Exception) {}
                        // peerID is ALWAYS the claimed logical author (packet.senderID) so
                        // SecurityManager/Noise verify the author key. For RSR, ingress already
                        // solicited against validationPeerID (= hop); previousHopPeerID carries
                        // that hop for any downstream re-check (iOS handleReceivedPacket split:
                        // hop for link liveness, packet.senderID for crypto).
                        _incoming.trySend(
                            RoutedPacket(
                                packet = packet,
                                peerID = claimedSenderID,
                                relayAddress = deviceAddress,
                                previousHopPeerID = context.receivedFromPeerID,
                            ),
                        )
                    }
                }
            }

            override fun onDeviceConnected(deviceAddress: String) {
                try {
                    val peer = cm.addressPeerMap[deviceAddress]
                    val nick = peer?.let { nicknameResolver?.invoke(it) } ?: "unknown"
                    val inbound = cm.isClientConnection(deviceAddress) == false
                    debugSettingsManager.logPeerConnection(peer ?: "unknown", nick, deviceAddress, inbound)
                } catch (_: Exception) {}
                // A link just came up — drain directed traffic parked while the radio was empty
                // (iOS flushDirectedSpool after subscribe/announce).
                try { cm.flushDirectedSpool() } catch (_: Exception) {}
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
        try { old.shutdown() } catch (_: Exception) {}
        // Detach the old stack's delegate: late asynchronous callbacks must neither emit stale
        // packets into the new generation's flow nor evict fresh address bindings.
        old.delegate = null
        this.myPeerID = myPeerID
        _neighbors.value = emptySet()
        lastRedundantLinkRetirementAt.clear()
        ingressRegistry.clear()
        connectionManager = connectionManagerFactory(myPeerID)
        wireConnectionManager()
        nicknameResolver?.let { connectionManager.setNicknameResolver(it) }
        connectionManager.setMeshServiceActive(meshServiceActive)
        connectionManager.setAppIsActive(appIsActive)
    }

    override fun start(): Boolean = connectionManager.startServices()
    override fun stop() = connectionManager.shutdown()
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
