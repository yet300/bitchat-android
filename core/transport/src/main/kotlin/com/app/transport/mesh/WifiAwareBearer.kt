package com.app.transport.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import com.app.common.encoding.toHexString
import com.app.crypto.EncryptionService
import com.app.transport.MeshTelemetry
import com.app.transport.debug.DebugSettingsManager
import com.app.transport.mesh.aware.SyncedSocket
import com.app.transport.mesh.aware.WifiAwareConnectionTracker
import com.app.transport.mesh.aware.WifiAwareSupport
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.MeshConstants
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.SpecialRecipients
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Inet6Address
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * Wi-Fi Aware (NAN) implementation of [MeshBearer].
 *
 * Networking core ported from upstream `WifiAwareMeshService` (wifi-aware branch):
 * publish/subscribe discovery, server/client NDP socket establishment over scoped IPv6,
 * deterministic role selection with role-reversal fallback, TCP + discovery keep-alives,
 * and periodic reconnection maintenance.
 *
 * Deliberate deltas from upstream:
 *  - No MeshCore/TransportBridge/Gossip coupling: received packets are emitted on
 *    [incoming]; peer lifecycle is surfaced via [neighbors] and [events]. The engine
 *    ([BluetoothMeshService]) reacts to [BearerEvent.LinkConnected] by broadcasting an
 *    ANNOUNCE, which replaces upstream's explicit post-connect announce + Noise initiation
 *    (handshakes are announce-driven in our engine).
 *  - Upstream's `WifiAwareController` object singleton is replaced by bearer-internal
 *    restart scheduling guarded by a session generation counter.
 *  - Link addresses are the pseudo-addresses `aware:<peerID>`; [bindPeer] ignores
 *    addresses without that prefix (bearer ownership rule).
 *
 * OCP: registering this bearer requires only [ContributesIntoSet] here — no changes to
 * [MeshNetwork], [BleBearer], or [BluetoothMeshService]. The peer ID is re-derived from
 * [EncryptionService] on every (re)start, so a panic reset only needs a bearer restart.
 */
@ContributesIntoSet(AppScope::class)
@SingleIn(AppScope::class)
@Inject
internal class WifiAwareBearer(
    private val context: Context,
    private val encryptionService: EncryptionService,
    debugSettingsManager: DebugSettingsManager,
    fragmentManager: FragmentManager,
    transferProgressManager: TransferProgressManager,
) : MeshBearer {

    private val telemetry: MeshTelemetry = debugSettingsManager

    companion object {
        private const val TAG = "WifiAwareBearer"
        private val MAX_TTL: UByte = MeshConstants.MESSAGE_TTL_HOPS
        private const val SERVICE_NAME = "bitchat"
        private const val PSK = "bitchat_secret"
        // Network request / socket timeouts
        private const val NETWORK_REQUEST_TIMEOUT_MS = 30_000
        private const val ACCEPT_TIMEOUT_MS = 30_000
        private const val CLIENT_CONNECT_TIMEOUT_MS = 7_000
        private const val CLIENT_SOCKET_READY_DELAY_MS = 750L
        private const val CLIENT_SOCKET_RETRY_DELAY_MS = 750L
        private const val CLIENT_SOCKET_ATTEMPTS = 3
        private const val CLIENT_ROLE_REVERSAL_FAILURES = 3
        // Discovery freshness window for reconnection maintenance
        private const val DISCOVERY_STALE_MS = 5L * 60 * 1000
        private const val DISCOVERY_IDLE_REFRESH_MS = 2L * 60 * 1000
        private const val DISCOVERY_SESSION_REFRESH_MIN_INTERVAL_MS = 90L * 1000
        private const val ROLE_REVERSAL_PREFIX = "ROLE_SERVER:"

        /** Pseudo link-address namespace owned by this bearer. */
        const val LINK_PREFIX = "aware:"
    }

    override val id: BearerId = BearerId.WIFI_AWARE

    // -----------------------------------------------------------------
    // MeshBearer surface
    // -----------------------------------------------------------------

    private val _incoming = MutableSharedFlow<RoutedPacket>(extraBufferCapacity = 64)
    override val incoming: Flow<RoutedPacket> = _incoming.asSharedFlow()

    private val _neighbors = MutableStateFlow<Set<PeerLink>>(emptySet())
    override val neighbors: StateFlow<Set<PeerLink>> = _neighbors.asStateFlow()

    private val _events = MutableSharedFlow<BearerEvent>(extraBufferCapacity = 64)
    override val events: Flow<BearerEvent> = _events.asSharedFlow()

    override fun bindPeer(peerID: String, linkAddress: String) {
        // Ownership rule: this bearer only tracks aware:* pseudo-addresses.
        if (!linkAddress.startsWith(LINK_PREFIX)) return
        _neighbors.update { links ->
            links.map { if (it.deviceAddress == linkAddress) it.copy(peerID = peerID) else it }.toSet()
        }
    }

    // -----------------------------------------------------------------
    // Bearer state
    // -----------------------------------------------------------------

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val listenerExec = Executors.newCachedThreadPool()
    private val fragmentingSender =
        FragmentingPacketSender(scope, fragmentManager, transferProgressManager, TAG)

    @Volatile private var myPeerID: String = ""

    private val awareManager get() = WifiAwareSupport.getManager(context)
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val connectionTracker = WifiAwareConnectionTracker(scope, cm)

    @Volatile private var wifiAwareSession: WifiAwareSession? = null
    @Volatile private var publishSession: PublishDiscoverySession? = null
    @Volatile private var subscribeSession: SubscribeDiscoverySession? = null

    /** User-level intent: start() was called and stop() has not been. */
    @Volatile private var started = false
    /** Radio-level state: an attach cycle is live. */
    @Volatile private var isActive = false
    @Volatile private var recoveryInProgress = false
    private val sessionGeneration = AtomicInteger(0)
    private val restartPending = AtomicBoolean(false)

    private val handleToPeerId = ConcurrentHashMap<PeerHandle, String>() // discovery mapping
    private val discoveredTimestamps = ConcurrentHashMap<String, Long>() // peerID -> last seen time
    // Subscribe-session-scoped handles only. PeerHandles are session-scoped, so a handle obtained
    // from the publish session is NOT valid for subscribeSession.sendMessage(). Maintenance re-pings
    // (subscriber -> publisher) must use a handle that originated from the subscribe session.
    private val subscribeHandles = ConcurrentHashMap<String, PeerHandle>()
    private val publishHandles = ConcurrentHashMap<String, PeerHandle>()
    private val forcedServerPeers = ConcurrentHashMap.newKeySet<String>()
    private val forcedClientPeers = ConcurrentHashMap.newKeySet<String>()
    private val clientSocketFailures = ConcurrentHashMap<String, AtomicInteger>()
    private val lastDiscoveryActivityAt = AtomicLong(0L)
    private val lastDiscoveryRefreshAt = AtomicLong(0L)

    private fun linkAddressFor(peerId: String): String = LINK_PREFIX + peerId

    // -----------------------------------------------------------------
    // Neighbor bookkeeping (replaces upstream meshCore.setDirectConnection/removePeer)
    // -----------------------------------------------------------------

    private fun onPeerSocketEstablished(peerId: String, inbound: Boolean) {
        val addr = linkAddressFor(peerId)
        _neighbors.update { links ->
            links.filterNot { it.peerID == peerId || it.deviceAddress == addr }.toSet() +
                PeerLink(peerId, addr, isInbound = inbound)
        }
        try { telemetry.logPeerConnection(peerId, "unknown", addr, inbound) } catch (_: Exception) { }
        _events.tryEmit(BearerEvent.LinkConnected(addr))
    }

    private fun onPeerLinkLost(peerId: String) {
        val addr = linkAddressFor(peerId)
        var hadLink = false
        _neighbors.update { links ->
            val filtered = links.filterNot { it.peerID == peerId || it.deviceAddress == addr }.toSet()
            hadLink = filtered.size != links.size
            filtered
        }
        if (hadLink) {
            try { telemetry.logPeerDisconnection(peerId, "unknown", addr) } catch (_: Exception) { }
            _events.tryEmit(BearerEvent.LinkDisconnected(addr))
        }
    }

    private fun onPeerRebound(previousPeerId: String, resolvedPeerId: String, inbound: Boolean) {
        _neighbors.update { links ->
            links.filterNot {
                it.peerID == previousPeerId || it.peerID == resolvedPeerId ||
                    it.deviceAddress == linkAddressFor(previousPeerId) ||
                    it.deviceAddress == linkAddressFor(resolvedPeerId)
            }.toSet() + PeerLink(resolvedPeerId, linkAddressFor(resolvedPeerId), isInbound = inbound)
        }
        _events.tryEmit(BearerEvent.LinkDisconnected(linkAddressFor(previousPeerId)))
        _events.tryEmit(BearerEvent.LinkConnected(linkAddressFor(resolvedPeerId)))
    }

    // -----------------------------------------------------------------
    // Send path
    // -----------------------------------------------------------------

    override fun broadcast(packet: RoutedPacket) {
        val routed = packet
        Log.d(TAG, "TX: packet type=${routed.packet.type} broadcast (ttl=${routed.packet.ttl})")

        val pkt = routed.packet
        if (pkt.senderID.toHexString() == myPeerID && !pkt.route.isNullOrEmpty()) {
            val firstHop = pkt.route!![0].toHexString()
            if (sendRoutedPacketToPeer(firstHop, routed)) {
                Log.d(TAG, "TX: source-routed packet sent only to first Wi-Fi hop ${firstHop.take(8)}")
                return
            }
            Log.w(TAG, "TX: first Wi-Fi source-route hop ${firstHop.take(8)} unavailable; falling back to broadcast")
        }

        val recipientId = pkt.recipientID?.toHexString()
        if (recipientId != null && !pkt.recipientID.contentEquals(SpecialRecipients.BROADCAST)) {
            if (sendRoutedPacketToPeer(recipientId, routed)) {
                Log.d(TAG, "TX: addressed packet sent directly to Wi-Fi peer ${recipientId.take(8)}")
                return
            }
        }

        fragmentingSender.send(routed, "Wi-Fi Aware broadcast") { single ->
            broadcastSinglePacket(single)
        }
    }

    override fun sendToPeer(peerID: String, packet: RoutedPacket): Boolean =
        sendRoutedPacketToPeer(peerID, packet)

    override fun cancelTransfer(transferId: String): Boolean =
        fragmentingSender.cancelTransfer(transferId)

    private fun sendRoutedPacketToPeer(peerID: String, routed: RoutedPacket): Boolean {
        if (connectionTracker.getSocketForPeer(peerID) == null) {
            return false
        }
        return fragmentingSender.send(routed, "Wi-Fi Aware peer ${peerID.take(8)}") { single ->
            sendSinglePacketToPeer(peerID, single.packet)
        }
    }

    private fun broadcastSinglePacket(routed: RoutedPacket): Boolean {
        val data = routed.packet.toBinaryData() ?: return false
        var sent = 0
        connectionTracker.peerSockets.forEach { (pid, sock) ->
            // Don't echo a relayed packet back over the link it arrived on
            if (routed.relayAddress != null && routed.relayAddress == linkAddressFor(pid)) return@forEach
            try {
                sock.write(data)
                sent++
            } catch (e: IOException) {
                Log.e(TAG, "TX: write failed to ${pid.take(8)}: ${e.message}")
            }
        }
        Log.d(TAG, "TX: broadcast via Wi-Fi Aware to $sent peers (bytes=${data.size})")
        return true
    }

    private fun sendSinglePacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
        val data = packet.toBinaryData() ?: return false
        val sock = connectionTracker.getSocketForPeer(peerID)
        if (sock == null) {
            Log.w(TAG, "TX: no socket for ${peerID.take(8)}")
            return false
        }
        return try {
            sock.write(data)
            Log.d(TAG, "TX: packet type=${packet.type} to ${peerID.take(8)} (bytes=${data.size})")
            true
        } catch (e: IOException) {
            Log.e(TAG, "TX: write to ${peerID.take(8)} failed: ${e.message}")
            false
        }
    }

    // -----------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        if (isActive) return true
        val supportStatus = WifiAwareSupport.evaluate(context)
        if (!supportStatus.supported) {
            Log.i(TAG, "Wi-Fi Aware unsupported on this device; not starting (${supportStatus.reason})")
            return false
        }
        if (!supportStatus.available) {
            Log.i(TAG, "Wi-Fi Aware unavailable right now; not starting (${supportStatus.reason})")
            return false
        }
        started = true
        return attachInternal()
    }

    override fun stop() {
        started = false
        val wasActive = isActive
        isActive = false
        sessionGeneration.incrementAndGet()
        if (!wasActive) return
        Log.i(TAG, "Stopping Wi-Fi Aware bearer")

        connectionTracker.stop() // Handles socket closing and callback unregistration

        try { publishSession?.close() } catch (_: Exception) { }
        publishSession = null
        try { subscribeSession?.close() } catch (_: Exception) { }
        subscribeSession = null
        try { wifiAwareSession?.close() } catch (_: Exception) { }
        wifiAwareSession = null

        handleToPeerId.clear()
        subscribeHandles.clear()
        publishHandles.clear()
        discoveredTimestamps.clear()
        forcedServerPeers.clear()
        forcedClientPeers.clear()
        clientSocketFailures.clear()
        _neighbors.value = emptySet()
    }

    private fun isCurrentSession(generation: Int): Boolean {
        return generation == sessionGeneration.get() && isActive
    }

    /** Replaces upstream's WifiAwareController.restartIfStillEnabled (coalesced restarts). */
    private fun scheduleRestart(delayMs: Long) {
        if (!started) return
        if (!restartPending.compareAndSet(false, true)) return
        scope.launch {
            delay(delayMs)
            restartPending.set(false)
            if (!started || isActive) return@launch
            if (recoveryInProgress) {
                scheduleRestart(500)
                return@launch
            }
            Log.i(TAG, "Restarting Wi-Fi Aware bearer after recovery")
            attachInternal()
        }
    }

    @SuppressLint("MissingPermission")
    private fun attachInternal(): Boolean {
        if (isActive) return true
        val supportStatus = WifiAwareSupport.evaluate(context)
        if (!supportStatus.supported || !supportStatus.available) {
            Log.i(TAG, "Wi-Fi Aware not usable; deferring (${supportStatus.reason})")
            if (started) scheduleRestart(5_000)
            return false
        }
        if (recoveryInProgress) {
            Log.i(TAG, "Wi-Fi Aware recovery cleanup still in progress; deferring start")
            scheduleRestart(500)
            return false
        }
        val manager = awareManager
        if (manager == null || !manager.isAvailable) {
            Log.w(TAG, "Wi-Fi Aware manager unavailable; not starting")
            return false
        }
        isActive = true
        myPeerID = encryptionService.getIdentityFingerprint().take(16)
        val startTime = System.currentTimeMillis()
        lastDiscoveryActivityAt.set(startTime)
        lastDiscoveryRefreshAt.set(startTime)
        val generation = sessionGeneration.incrementAndGet()
        Log.i(TAG, "Starting Wi-Fi Aware mesh with peer ID: $myPeerID")

        try {
            manager.attach(object : AttachCallback() {
                @SuppressLint("MissingPermission")
                override fun onAttached(session: WifiAwareSession) {
                    if (!isCurrentSession(generation)) {
                        session.close()
                        return
                    }
                    wifiAwareSession = session
                    Log.i(TAG, "Wi-Fi Aware attached; starting publish & subscribe (peerID=$myPeerID)")
                    startPublish(session, generation)
                    startSubscribe(session, generation)
                }

                override fun onAttachFailed() {
                    if (!isCurrentSession(generation)) return
                    Log.e(TAG, "Wi-Fi Aware attach failed")
                    handleUnexpectedStop(generation)
                    scheduleRestart(3000)
                }

                override fun onAwareSessionTerminated() {
                    if (!isCurrentSession(generation)) return
                    Log.e(TAG, "Aware Session Terminated unexpectedly")
                    wifiAwareSession = null
                    handleUnexpectedStop(generation)
                    scheduleRestart(3000)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.e(TAG, "Wi-Fi Aware attach threw: ${e.message}")
            isActive = false
            return false
        }

        startPeriodicConnectionMaintenance()
        connectionTracker.start()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun startPublish(session: WifiAwareSession, generation: Int) {
        session.publish(
            PublishConfig.Builder()
                .setServiceName(SERVICE_NAME)
                .setServiceSpecificInfo(myPeerID.toByteArray())
                .build(),
            object : DiscoverySessionCallback() {
                override fun onPublishStarted(pub: PublishDiscoverySession) {
                    if (!isCurrentSession(generation)) {
                        pub.close()
                        return
                    }
                    publishSession = pub
                    Log.d(TAG, "PUBLISH: onPublishStarted()")
                }

                override fun onServiceDiscovered(
                    peerHandle: PeerHandle,
                    serviceSpecificInfo: ByteArray,
                    matchFilter: List<ByteArray>
                ) {
                    if (!isCurrentSession(generation)) return
                    val peerId = try { String(serviceSpecificInfo) } catch (_: Exception) { "" }
                    handleToPeerId[peerHandle] = peerId
                    if (peerId.isNotBlank()) {
                        rememberDiscoveredPeer(peerId)
                        publishHandles[peerId] = peerHandle
                        Log.i(TAG, "PUBLISH: Discovered subscriber '$peerId' via Aware")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            offerServerPathIfAppropriate(peerId, peerHandle, "publish discovery")
                        }
                    }
                }

                @RequiresApi(Build.VERSION_CODES.Q)
                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    if (!isCurrentSession(generation)) return
                    if (message.isEmpty()) return
                    val subscriberId = try { String(message) } catch (_: Exception) { "" }
                    if (subscriberId.startsWith(ROLE_REVERSAL_PREFIX)) {
                        val requesterId = subscriberId.removePrefix(ROLE_REVERSAL_PREFIX)
                        handleRoleReversalRequest(peerHandle, requesterId)
                        return
                    }
                    if (subscriberId == myPeerID) return

                    handleToPeerId[peerHandle] = subscriberId
                    if (subscriberId.isNotBlank()) {
                        rememberDiscoveredPeer(subscriberId)
                        publishHandles[subscriberId] = peerHandle
                    }
                    Log.i(TAG, "PUBLISH: Received discovery ping from subscriber '$subscriberId'")
                    publishSession?.let { handleSubscriberPing(it, peerHandle) }
                }

                override fun onSessionTerminated() {
                    if (!isCurrentSession(generation)) return
                    Log.e(TAG, "PUBLISH: onSessionTerminated()")
                    publishSession = null
                    handleUnexpectedStop(generation)
                    scheduleRestart(2000)
                }
            },
            Handler(Looper.getMainLooper())
        )
    }

    @SuppressLint("MissingPermission")
    private fun startSubscribe(session: WifiAwareSession, generation: Int) {
        session.subscribe(
            SubscribeConfig.Builder()
                .setServiceName(SERVICE_NAME)
                .setServiceSpecificInfo(myPeerID.toByteArray(Charsets.UTF_8))
                .build(),
            object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(sub: SubscribeDiscoverySession) {
                    if (!isCurrentSession(generation)) {
                        sub.close()
                        return
                    }
                    subscribeSession = sub
                    Log.d(TAG, "SUBSCRIBE: onSubscribeStarted()")
                }

                override fun onServiceDiscovered(
                    peerHandle: PeerHandle,
                    serviceSpecificInfo: ByteArray,
                    matchFilter: List<ByteArray>
                ) {
                    if (!isCurrentSession(generation)) return
                    val peerId = try { String(serviceSpecificInfo) } catch (_: Exception) { "" }
                    handleToPeerId[peerHandle] = peerId
                    // This handle came from the subscribe session, so it is valid for
                    // subscribeSession.sendMessage() (used by maintenance reconnection).
                    if (peerId.isNotBlank()) subscribeHandles[peerId] = peerHandle
                    sendSubscribePing(peerId, peerHandle, "discovery")
                    if (peerId.isNotBlank()) rememberDiscoveredPeer(peerId)
                }

                @RequiresApi(Build.VERSION_CODES.Q)
                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    if (!isCurrentSession(generation)) return
                    if (message.isEmpty()) return
                    handleServerReady(peerHandle, message)
                }

                override fun onSessionTerminated() {
                    if (!isCurrentSession(generation)) return
                    Log.e(TAG, "SUBSCRIBE: onSessionTerminated()")
                    subscribeSession = null
                    handleUnexpectedStop(generation)
                    scheduleRestart(2000)
                }
            },
            Handler(Looper.getMainLooper())
        )
    }

    private fun handleUnexpectedStop(generation: Int) {
        if (generation != sessionGeneration.get()) return
        if (!isActive) return
        recoveryInProgress = true
        isActive = false
        _neighbors.value = emptySet()
        val oldPublishSession = publishSession
        val oldSubscribeSession = subscribeSession
        val oldWifiAwareSession = wifiAwareSession
        scope.launch {
            try {
                try { connectionTracker.stop() } catch (_: Exception) { }
                try { oldPublishSession?.close() } catch (_: Exception) { }
                try { oldSubscribeSession?.close() } catch (_: Exception) { }
                try { oldWifiAwareSession?.close() } catch (_: Exception) { }
                if (generation == sessionGeneration.get() && !isActive) {
                    if (publishSession === oldPublishSession) publishSession = null
                    if (subscribeSession === oldSubscribeSession) subscribeSession = null
                    if (wifiAwareSession === oldWifiAwareSession) wifiAwareSession = null
                    handleToPeerId.clear()
                    subscribeHandles.clear()
                    publishHandles.clear()
                    discoveredTimestamps.clear()
                }
            } finally {
                recoveryInProgress = false
                // Recovery cleanup is done; nudge a restart now that attachInternal() will no
                // longer be deferred by recoveryInProgress. scheduleRestart coalesces requests.
                scheduleRestart(500)
            }
        }
    }

    private fun rememberDiscoveredPeer(peerId: String) {
        if (peerId.isBlank() || peerId == myPeerID) return
        val now = System.currentTimeMillis()
        discoveredTimestamps[peerId] = now
        lastDiscoveryActivityAt.set(now)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun offerServerPathIfAppropriate(peerId: String, peerHandle: PeerHandle, reason: String) {
        val pubSession = publishSession ?: return
        if (peerId.isBlank() || peerId == myPeerID || !amIServerFor(peerId)) return
        if (!connectionTracker.isConnectionAttemptAllowed(peerId)) return

        Log.d(TAG, "PUBLISH: offering server path to ${peerId.take(8)} after $reason")
        handleSubscriberPing(pubSession, peerHandle)
    }

    private fun refreshDiscoverySessions(reason: String, now: Long = System.currentTimeMillis()): Boolean {
        if (!isActive || recoveryInProgress) return false

        val lastRefresh = lastDiscoveryRefreshAt.get()
        if ((now - lastRefresh) < DISCOVERY_SESSION_REFRESH_MIN_INTERVAL_MS) return false
        if (!lastDiscoveryRefreshAt.compareAndSet(lastRefresh, now)) return false

        Log.i(TAG, "Maintenance: refreshing Wi-Fi Aware discovery sessions ($reason)")
        handleUnexpectedStop(sessionGeneration.get())
        return true
    }

    /**
     * Periodic active maintenance: retries connections to discovered but unconnected peers.
     */
    private fun startPeriodicConnectionMaintenance() {
        scope.launch {
            Log.d(TAG, "Starting periodic connection maintenance loop")
            while (isActive) {
                try {
                    delay(15_000) // Check every 15 seconds
                    if (!isActive) break

                    val now = System.currentTimeMillis()

                    // 0. Prune stale discovery entries. PeerHandles become invalid when the
                    // discovery sessions restart, so we must not keep pinging old handles forever.
                    val staleIds = discoveredTimestamps.filter { (id, ts) ->
                        (now - ts) >= DISCOVERY_STALE_MS && !connectionTracker.isConnected(id)
                    }.keys.toSet()
                    if (staleIds.isNotEmpty()) {
                        staleIds.forEach { discoveredTimestamps.remove(it) }
                        handleToPeerId.entries.removeIf { it.value in staleIds }
                        staleIds.forEach { subscribeHandles.remove(it) }
                        staleIds.forEach { publishHandles.remove(it) }
                        Log.d(TAG, "Maintenance: pruned ${staleIds.size} stale discovery entries")
                    }

                    // 1. Identify peers that are discovered (recently seen) but not currently connected
                    val recentDiscovered = discoveredTimestamps.filter { (_, ts) ->
                        (now - ts) < DISCOVERY_STALE_MS // Seen in last 5 minutes
                    }.keys

                    // 2. Filter out those who are already connected
                    val disconnectedPeers = recentDiscovered.filter { peerId ->
                        !connectionTracker.isConnected(peerId)
                    }

                    // 3. Attempt reconnection. Aware discovery is not always symmetrical:
                    // subscribe handles can disappear while publish handles still see the peer.
                    var attemptedReconnect = false
                    var missingUsableHandle = false
                    for (peerId in disconnectedPeers) {
                        if (amIServerFor(peerId)) {
                            val handle = publishHandles[peerId]
                            if (handle == null) {
                                missingUsableHandle = true
                                continue
                            }
                            if (!connectionTracker.isConnectionAttemptAllowed(peerId)) continue
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                Log.i(TAG, "Maintenance: offering Wi-Fi Aware server path to ${peerId.take(8)}")
                                offerServerPathIfAppropriate(peerId, handle, "maintenance")
                                attemptedReconnect = true
                            }
                            continue
                        }

                        // Use a subscribe-session-scoped handle. A publish-scoped handle would be
                        // invalid for subscribeSession.sendMessage() and silently fail.
                        val handle = subscribeHandles[peerId]
                        if (handle == null) {
                            missingUsableHandle = true
                            continue
                        }

                        // Check tracker policy
                        if (!connectionTracker.isConnectionAttemptAllowed(peerId)) continue

                        Log.i(TAG, "Maintenance: attempting Wi-Fi Aware reconnect to ${peerId.take(8)}")
                        sendSubscribePing(peerId, handle, "maintenance")
                        attemptedReconnect = true
                    }

                    val noActiveDataPath = connectionTracker.getConnectionCount() == 0 &&
                        !connectionTracker.hasPendingDataPathRequest()
                    if (noActiveDataPath) {
                        val idleFor = now - lastDiscoveryActivityAt.get()
                        when {
                            disconnectedPeers.isNotEmpty() && missingUsableHandle && !attemptedReconnect -> {
                                refreshDiscoverySessions("missing peer handle", now)
                            }
                            recentDiscovered.isEmpty() && idleFor >= DISCOVERY_IDLE_REFRESH_MS -> {
                                refreshDiscoverySessions("idle discovery", now)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in connection maintenance: ${e.message}")
                }
            }
        }
    }

    private fun sendSubscribePing(peerId: String, peerHandle: PeerHandle, reason: String) {
        if (peerId.isBlank()) return
        val msgId = (System.nanoTime() and 0x7fffffff).toInt()
        try {
            subscribeSession?.sendMessage(peerHandle, msgId, myPeerID.toByteArray())
            Log.d(TAG, "SUBSCRIBE: sent $reason ping to '${peerId.take(16)}' (msgId=$msgId)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send $reason ping to ${peerId.take(8)}: ${e.message}")
        }
    }

    private fun requestRoleReversal(peerId: String, allowForcedClientOverride: Boolean = false) {
        if (peerId.isBlank()) return
        if (forcedClientPeers.contains(peerId) && !allowForcedClientOverride) return
        forcedServerPeers.add(peerId)
        forcedClientPeers.remove(peerId)

        val handle = subscribeHandles[peerId]
        if (handle == null) {
            Log.i(TAG, "CLIENT: role reversal queued for ${peerId.take(8)} until subscribe handle is available")
            return
        }

        val msgId = (System.nanoTime() and 0x7fffffff).toInt()
        val payload = "$ROLE_REVERSAL_PREFIX$myPeerID".toByteArray()
        try {
            subscribeSession?.sendMessage(handle, msgId, payload)
            Log.i(TAG, "CLIENT: requested Wi-Fi Aware role reversal with ${peerId.take(8)} (msgId=$msgId)")
        } catch (e: Exception) {
            Log.w(TAG, "CLIENT: failed to request role reversal with ${peerId.take(8)}: ${e.message}")
        }
    }

    private fun shouldRequestRoleReversalAfterClientFailure(peerId: String): Boolean {
        val failures = clientSocketFailures
            .computeIfAbsent(peerId) { AtomicInteger(0) }
            .incrementAndGet()
        val shouldReverse = failures >= CLIENT_ROLE_REVERSAL_FAILURES
        if (shouldReverse) {
            clientSocketFailures.remove(peerId)
            Log.i(TAG, "CLIENT: ${peerId.take(8)} failed $failures client socket attempts; requesting role reversal")
        } else {
            Log.d(TAG, "CLIENT: ${peerId.take(8)} failed client socket attempt $failures/$CLIENT_ROLE_REVERSAL_FAILURES; retrying same role")
        }
        return shouldReverse
    }

    private fun handleRoleReversalRequest(peerHandle: PeerHandle, requesterId: String) {
        if (requesterId.isBlank() || requesterId == myPeerID) return
        handleToPeerId[peerHandle] = requesterId
        discoveredTimestamps[requesterId] = System.currentTimeMillis()
        forcedClientPeers.add(requesterId)
        forcedServerPeers.remove(requesterId)
        Log.i(TAG, "PUBLISH: role reversal requested by ${requesterId.take(8)}; switching to client role")

        subscribeHandles[requesterId]?.let { handle ->
            sendSubscribePing(requesterId, handle, "role-reversal")
        }
    }

    /**
     * Handles subscriber ping: spawns a server socket and responds with connection info.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun handleSubscriberPing(
        pubSession: PublishDiscoverySession,
        peerHandle: PeerHandle
    ) {
        val peerId = handleToPeerId[peerHandle] ?: return
        if (!amIServerFor(peerId)) return

        if (connectionTracker.isConnected(peerId)) {
            Log.v(TAG, "↪ already connected to $peerId, skipping serve")
            return
        }
        if (connectionTracker.hasOpenServerSocket(peerId)) {
            Log.v(TAG, "↪ already serving $peerId, skipping")
            return
        }
        if (connectionTracker.hasPendingDataPathRequest(peerId)) {
            val pending = connectionTracker.pendingDataPathPeerIds(peerId).joinToString(", ") { it.take(8) }
            Log.d(TAG, "SERVER: deferring serve for ${peerId.take(8)}; pending Aware data path(s): $pending")
            return
        }
        if (!connectionTracker.addPendingConnection(peerId)) {
            return
        }

        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            val anyIpv6 = Inet6Address.getByAddress(ByteArray(16))
            ss.bind(java.net.InetSocketAddress(anyIpv6, 0))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind server socket", e)
            handleNetworkFailure(peerId)
            return
        }

        connectionTracker.addServerSocket(peerId, ss)
        val port = ss.localPort

        Log.d(TAG, "SERVER: listening for ${peerId.take(8)} on ${ss.localSocketAddress}")

        val spec = WifiAwareNetworkSpecifier.Builder(pubSession, peerHandle)
            .setPskPassphrase(PSK)
            .setPort(port)
            .setTransportProtocol(OsConstants.IPPROTO_TCP)
            .build()
        // Default capabilities include NET_CAPABILITY_NOT_VPN.
        // Keeping defaults for hardware interface handle acquisition compatibility with global VPNs.
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(spec)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            @Volatile private var activeSocket: SyncedSocket? = null
            private val acceptStarted = AtomicBoolean(false)

            override fun onAvailable(network: Network) {
                Log.i(TAG, "SERVER: onAvailable() - Aware network is ready for ${peerId.take(8)}")
                // Only accept once per network request
                if (!acceptStarted.compareAndSet(false, true)) return
                // Offload the blocking accept() off the callback thread so we never stall
                // the (main-thread) ConnectivityManager callback dispatcher.
                listenerExec.execute {
                    try {
                        try { ss.soTimeout = ACCEPT_TIMEOUT_MS } catch (_: Exception) {}
                        val client = ss.accept()
                        Log.i(TAG, "SERVER: Accepted raw TCP connection from ${peerId.take(8)}")
                        try { network.bindSocket(client) } catch (e: Exception) { Log.w(TAG, "Server bindSocket EPERM: ${e.message}") }
                        client.keepAlive = true
                        Log.i(TAG, "SERVER: Bound and established TCP with ${peerId.take(8)} addr=${client.inetAddress?.hostAddress}")
                        val synced = SyncedSocket(client)
                        activeSocket = synced
                        connectionTracker.onClientConnected(peerId, synced)
                        // We only ever accept a single data socket per server request. Close the
                        // listening ServerSocket now so it can't block a future re-serve (its
                        // presence makes hasOpenServerSocket() true for the life of the process)
                        // and so we free the fd/port promptly.
                        connectionTracker.closeServerSocket(peerId)
                        onPeerSocketEstablished(peerId, inbound = true)
                        listenerExec.execute { listenToPeer(synced, peerId) }
                        handleSubscriberKeepAlive(synced, peerId, pubSession, peerHandle)
                    } catch (ioe: IOException) {
                        if (ss.isClosed || !isActive) {
                            Log.d(TAG, "SERVER: accept stopped for ${peerId.take(8)} after socket cleanup")
                        } else {
                            Log.e(TAG, "SERVER: accept failed for ${peerId.take(8)}", ioe)
                            handleNetworkFailure(peerId)
                        }
                    }
                }
            }

            override fun onUnavailable() {
                Log.e(TAG, "SERVER: onUnavailable() - Failed to acquire Aware network for ${peerId.take(8)} (timeout or refused)")
                handleNetworkFailure(peerId)
            }

            override fun onLost(network: Network) {
                handlePeerDisconnection(peerId, activeSocket)
                Log.i(TAG, "SERVER: WiFi Aware network lost for ${peerId.take(8)}")
            }
        }

        connectionTracker.addNetworkCallback(peerId, cb)
        Log.i(TAG, "SERVER: [Calling requestNetwork] for ${peerId.take(8)} with port $port")
        try {
            // use requestNetwork with a timeout to trigger onUnavailable if it fails
            cm.requestNetwork(req, cb, NETWORK_REQUEST_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "SERVER: ConnectivityManager.requestNetwork threw exception", e)
            connectionTracker.disconnect(peerId)
        }

        val readyId = (System.nanoTime() and 0x7fffffff).toInt()
        val readyPayload = buildServerReadyPayload(port)
        Handler(Looper.getMainLooper()).post {
            try {
                pubSession.sendMessage(peerHandle, readyId, readyPayload)
                Log.d(TAG, "PUBLISH: server-ready sent (msgId=$readyId, port=$port)")
            } catch (e: Exception) {
                Log.e(TAG, "PUBLISH: Exception sending server-ready to $peerHandle", e)
            }
        }
    }

    /**
     * Sends periodic TCP and discovery keep-alive messages to maintain a subscriber connection.
     */
    private fun handleSubscriberKeepAlive(
        client: SyncedSocket,
        peerId: String,
        pubSession: PublishDiscoverySession,
        peerHandle: PeerHandle
    ) {
        // TCP keep-alive pings
        scope.launch {
            try {
                while (connectionTracker.isConnected(peerId)) {
                    // write empty byte array effectively sends [4 bytes length=0] which is our ping
                    try {
                        client.write(ByteArray(0))
                    } catch (_: IOException) {
                        // The write side is dead. Don't just stop pinging: actively tear down so the
                        // half-open socket stops counting as "connected" and maintenance can retry.
                        handlePeerDisconnection(peerId, client)
                        break
                    }
                    delay(2_000)
                }
            } catch (_: Exception) {}
        }
        // Discovery keep-alive
        scope.launch {
            var msgId = 0
            while (connectionTracker.isConnected(peerId)) {
                try { pubSession.sendMessage(peerHandle, msgId++, ByteArray(0)) } catch (_: Exception) { break }
                delay(20_000)
            }
        }
    }

    private fun connectAwareClientSocket(
        network: Network,
        scopedAddr: Inet6Address,
        port: Int,
        peerId: String
    ): Socket {
        var lastFailure: IOException? = null
        for (attempt in 1..CLIENT_SOCKET_ATTEMPTS) {
            val delayMs = if (attempt == 1) CLIENT_SOCKET_READY_DELAY_MS else CLIENT_SOCKET_RETRY_DELAY_MS
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Interrupted before Wi-Fi Aware socket connect")
                }
            }

            var sock: Socket? = null
            try {
                sock = network.socketFactory.createSocket()
                sock.tcpNoDelay = true
                sock.keepAlive = true
                sock.connect(java.net.InetSocketAddress(scopedAddr, port), CLIENT_CONNECT_TIMEOUT_MS)
                if (attempt > 1) {
                    Log.i(TAG, "CLIENT: socket connect succeeded for ${peerId.take(8)} on attempt $attempt")
                }
                return sock
            } catch (e: IOException) {
                lastFailure = e
                try { sock?.close() } catch (_: Exception) { }
                if (attempt < CLIENT_SOCKET_ATTEMPTS) {
                    Log.w(TAG, "CLIENT: socket attempt $attempt/$CLIENT_SOCKET_ATTEMPTS failed for ${peerId.take(8)}: ${e.message}; retrying")
                }
            }
        }

        throw lastFailure ?: IOException("Wi-Fi Aware socket connect failed without an exception")
    }

    private fun buildServerReadyPayload(port: Int): ByteArray {
        val peerIdBytes = myPeerID.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(Int.SIZE_BYTES + peerIdBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(port)
            .put(peerIdBytes)
            .array()
    }

    private fun peerIdFromServerReadyPayload(payload: ByteArray): String? {
        if (payload.size <= Int.SIZE_BYTES) return null
        val peerId = try {
            String(payload.copyOfRange(Int.SIZE_BYTES, payload.size), Charsets.UTF_8).trim()
        } catch (_: Exception) {
            return null
        }
        return peerId.takeIf { id ->
            id.length == 16 && id.all { ch -> ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' }
        }?.lowercase()
    }

    private fun resolveServerReadyPeerId(peerHandle: PeerHandle, payload: ByteArray): String? {
        val advertisedPeerId = peerIdFromServerReadyPayload(payload)
        val mappedPeerId = handleToPeerId[peerHandle]?.takeIf { it.isNotBlank() }
        val peerId = advertisedPeerId ?: mappedPeerId
        if (peerId == null) {
            Log.w(TAG, "SUBSCRIBE: dropped server-ready with no peer mapping and no peer ID payload (payload=${payload.size}B)")
            return null
        }

        handleToPeerId[peerHandle] = peerId
        subscribeHandles[peerId] = peerHandle
        rememberDiscoveredPeer(peerId)
        if (advertisedPeerId != null && mappedPeerId != null && advertisedPeerId != mappedPeerId) {
            Log.d(TAG, "SUBSCRIBE: server-ready remapped handle ${mappedPeerId.take(8)} -> ${advertisedPeerId.take(8)}")
        }
        return peerId
    }

    /**
     * Handles a "server ready" message from a publishing peer and initiates a client connection.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun handleServerReady(
        peerHandle: PeerHandle,
        payload: ByteArray
    ) {
        if (payload.size < Int.SIZE_BYTES) {
            Log.w(TAG, "handleServerReady called with invalid payload size=${payload.size}, dropping")
            return
        }

        val peerId = resolveServerReadyPeerId(peerHandle, payload) ?: return
        if (peerId == myPeerID) return
        if (amIServerFor(peerId)) return
        if (connectionTracker.peerSockets.containsKey(peerId)) {
            Log.v(TAG, "↪ already client-connected to $peerId, skipping")
            return
        }
        val cancelledServerOffers = connectionTracker.cancelPendingServerDataPaths(peerId)
        if (cancelledServerOffers.isNotEmpty()) {
            val cancelled = cancelledServerOffers.joinToString(", ") { it.take(8) }
            Log.i(TAG, "CLIENT: preempted pending server offer(s) for $cancelled to connect ${peerId.take(8)}")
        }
        if (connectionTracker.hasPendingDataPathRequest(peerId)) {
            val pending = connectionTracker.pendingDataPathPeerIds(peerId).joinToString(", ") { it.take(8) }
            Log.d(TAG, "CLIENT: deferring server-ready for ${peerId.take(8)}; pending Aware data path(s): $pending")
            return
        }
        if (!connectionTracker.addPendingConnection(peerId)) {
            return
        }

        val port = ByteBuffer.wrap(payload, 0, Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).int
        Log.i(TAG, "CLIENT: Received server-ready from ${peerId.take(8)} on port $port (payload=${payload.size}B). Requesting network...")

        val subSession = subscribeSession ?: run {
            Log.w(TAG, "CLIENT: subscribe session missing for server-ready from ${peerId.take(8)}")
            connectionTracker.removePendingConnection(peerId)
            return
        }
        val spec = WifiAwareNetworkSpecifier.Builder(subSession, peerHandle)
            .setPskPassphrase(PSK)
            .build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(spec)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            @Volatile private var activeSocket: SyncedSocket? = null
            private val connectStarted = AtomicBoolean(false)

            override fun onAvailable(network: Network) {
                Log.i(TAG, "CLIENT: onAvailable() - Aware network is ready for ${peerId.take(8)}")
                // Do not bind process for Aware; use per-socket binding instead
            }

            override fun onUnavailable() {
                Log.e(TAG, "CLIENT: onUnavailable() - Failed to acquire Aware network for ${peerId.take(8)}")
                if (shouldRequestRoleReversalAfterClientFailure(peerId)) {
                    requestRoleReversal(peerId, allowForcedClientOverride = true)
                }
                handleNetworkFailure(peerId)
            }

            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
                if (connectionTracker.peerSockets.containsKey(peerId)) return
                val info = (nc.transportInfo as? WifiAwareNetworkInfo) ?: return
                val addr = info.peerIpv6Addr ?: return
                val connectPort = if (info.port > 0) info.port else port
                // onCapabilitiesChanged can fire multiple times; only connect once
                if (!connectStarted.compareAndSet(false, true)) return
                Log.i(TAG, "CLIENT: onCapabilitiesChanged() - Peer IPv6 discovered: $addr port=$connectPort")

                val lp = cm.getLinkProperties(network)
                val iface = lp?.interfaceName

                // Offload the blocking connect() off the callback thread.
                listenerExec.execute {
                    try {
                        // Use scoped IPv6 if interface name is available
                        val scopedAddr = if (iface != null && addr.scopeId == 0) {
                            try {
                                Inet6Address.getByAddress(null, addr.address, java.net.NetworkInterface.getByName(iface))
                            } catch (e: Exception) {
                                addr
                            }
                        } else {
                            addr
                        }

                        val sock = connectAwareClientSocket(network, scopedAddr, connectPort, peerId)
                        Log.i(TAG, "CLIENT: TCP connected to ${peerId.take(8)} at $scopedAddr:$connectPort")

                        val synced = SyncedSocket(sock)
                        activeSocket = synced
                        connectionTracker.onClientConnected(peerId, synced)
                        clientSocketFailures.remove(peerId)
                        onPeerSocketEstablished(peerId, inbound = false)
                        listenerExec.execute { listenToPeer(synced, peerId) }
                        handleServerKeepAlive(synced, peerId, peerHandle)
                    } catch (ioe: IOException) {
                        Log.e(TAG, "CLIENT: socket connect failed to ${peerId.take(8)}", ioe)
                        if (shouldRequestRoleReversalAfterClientFailure(peerId)) {
                            requestRoleReversal(peerId, allowForcedClientOverride = true)
                        }
                        handleNetworkFailure(peerId)
                    }
                }
            }

            override fun onLost(network: Network) {
                handlePeerDisconnection(peerId, activeSocket)
                Log.i(TAG, "CLIENT: WiFi Aware network lost for ${peerId.take(8)}")
            }
        }

        connectionTracker.addNetworkCallback(peerId, cb)
        Log.i(TAG, "CLIENT: [Calling requestNetwork] for ${peerId.take(8)}")
        try {
            cm.requestNetwork(req, cb, NETWORK_REQUEST_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "CLIENT: ConnectivityManager.requestNetwork threw exception", e)
            connectionTracker.disconnect(peerId)
        }
    }

    /**
     * Sends periodic TCP and discovery keep-alive messages for server connections.
     */
    private fun handleServerKeepAlive(
        sock: SyncedSocket,
        peerId: String,
        peerHandle: PeerHandle
    ) {
        // TCP keep-alive
        scope.launch {
            try {
                while (connectionTracker.isConnected(peerId)) {
                    try {
                        sock.write(ByteArray(0))
                    } catch (_: IOException) {
                        // The write side is dead. Tear down so the half-open socket stops counting
                        // as "connected" and maintenance can retry instead of silently stalling.
                        handlePeerDisconnection(peerId, sock)
                        break
                    }
                    delay(2_000.milliseconds)
                }
            } catch (_: Exception) {}
        }
        // Discovery keep-alive
        scope.launch {
            var msgId = 0
            while (connectionTracker.isConnected(peerId)) {
                try { subscribeSession?.sendMessage(peerHandle, msgId++, ByteArray(0)) } catch (_: Exception) { break }
                delay(20_000.milliseconds)
            }
        }
    }

    /**
     * Determines whether this device should act as the server in a given peer relationship.
     */
    private fun amIServerFor(peerId: String): Boolean = when {
        forcedClientPeers.contains(peerId) -> false
        forcedServerPeers.contains(peerId) -> true
        else -> myPeerID < peerId
    }

    /**
     * Listens for incoming packets from a connected peer and emits them on [incoming].
     *
     * @param socket Socket connected to the peer
     * @param initialLogicalPeerId Temporary identifier before peer ID resolution
     */
    private fun listenToPeer(socket: SyncedSocket, initialLogicalPeerId: String) {
        var logicalPeerId = initialLogicalPeerId
        while (isActive) {
            val raw = socket.read() ?: break

            if (raw.isEmpty()) {
                // Keep-alive (0 length frame)
                continue
            }

            val pkt = BitchatPacket.fromBinaryData(raw) ?: continue

            val senderPeerHex = pkt.senderID.toHexString().take(16)

            if (pkt.type == MessageType.ANNOUNCE.value && pkt.ttl >= MAX_TTL && senderPeerHex != logicalPeerId) {
                val previousPeerId = logicalPeerId
                val wasInbound = _neighbors.value
                    .firstOrNull { it.peerID == previousPeerId }?.isInbound ?: amIServerFor(senderPeerHex)
                logicalPeerId = connectionTracker.rebindPeerId(previousPeerId, senderPeerHex, socket)
                handleToPeerId.forEach { (handle, peerId) ->
                    if (peerId == previousPeerId) {
                        handleToPeerId[handle] = senderPeerHex
                    }
                }
                subscribeHandles.remove(previousPeerId)?.let { subscribeHandles[senderPeerHex] = it }
                discoveredTimestamps.remove(previousPeerId)
                discoveredTimestamps[senderPeerHex] = System.currentTimeMillis()
                publishHandles.remove(previousPeerId)?.let { publishHandles[senderPeerHex] = it }
                onPeerRebound(previousPeerId, senderPeerHex, wasInbound)
                Log.i(TAG, "RX: rebound Wi-Fi direct peer ${previousPeerId.take(8)} -> ${senderPeerHex.take(8)}")
            }

            // Route the packet:
            // - peerID = Originator (who signed it)
            // - relayAddress = Neighbor link (who sent it to us over this socket)
            Log.d(TAG, "RX: packet type=${pkt.type} from ${senderPeerHex.take(8)} via ${logicalPeerId.take(8)} (bytes=${raw.size})")
            try {
                telemetry.logIncoming(
                    packet = pkt,
                    fromPeerID = senderPeerHex,
                    fromNickname = null,
                    fromDeviceAddress = linkAddressFor(logicalPeerId),
                    myPeerID = myPeerID,
                )
            } catch (_: Exception) { }
            _incoming.tryEmit(RoutedPacket(pkt, senderPeerHex, linkAddressFor(logicalPeerId)))
        }

        // Breaking out of the loop means the socket is dead or service is stopping.
        Log.i(TAG, "Socket loop terminated for ${logicalPeerId.take(8)} removing peer.")
        handlePeerDisconnection(logicalPeerId, socket)
        socket.close()
    }

    private fun handleNetworkFailure(peerId: String) {
        scope.launch {
            Log.d(TAG, "Network failure cleanup for: $peerId")
            if (!connectionTracker.isConnected(peerId)) {
                val canonicalPeerId = connectionTracker.canonicalPeerId(peerId)
                connectionTracker.disconnect(peerId)
                onPeerLinkLost(canonicalPeerId)
                if (canonicalPeerId != peerId) {
                    onPeerLinkLost(peerId)
                }
            } else {
                Log.d(TAG, "Network failure ignored for $peerId - another socket is active")
            }
        }
    }

    private fun handlePeerDisconnection(initialId: String, socket: SyncedSocket? = null) {
        scope.launch {
            // Check if this socket is the current active one before nuking the session
            val currentSocket = connectionTracker.getSocketForPeer(initialId)
            val canonicalPeerId = connectionTracker.canonicalPeerId(initialId)
            if (currentSocket === socket) {
                Log.d(TAG, "Cleaning up peer: $canonicalPeerId (active socket)")
                connectionTracker.disconnect(initialId)
                onPeerLinkLost(canonicalPeerId)
                if (canonicalPeerId != initialId) {
                    onPeerLinkLost(initialId)
                }
            } else if (socket == null && currentSocket == null) {
                // Fallback: If we don't have a specific socket context but we are already disconnected, ensure cleanup
                Log.d(TAG, "Cleaning up peer: $initialId (no active socket)")
                connectionTracker.disconnect(initialId)
                onPeerLinkLost(canonicalPeerId)
                if (canonicalPeerId != initialId) {
                    onPeerLinkLost(initialId)
                }
            } else {
                Log.d(TAG, "Ignored disconnection for $initialId - socket replaced or inactive")
                // Do not remove peer/session, as a new socket has likely taken over
            }
        }
    }

    fun debugInfo(): String = buildString {
        appendLine("=== Wi-Fi Aware Bearer ===")
        appendLine("started=$started active=$isActive generation=${sessionGeneration.get()}")
        appendLine("myPeerID=$myPeerID")
        append(connectionTracker.getDebugInfo())
    }
}
