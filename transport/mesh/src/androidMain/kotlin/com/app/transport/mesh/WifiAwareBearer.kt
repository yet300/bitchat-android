package com.app.transport.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import com.app.common.encoding.toHexString
import com.app.common.utils.Log
import com.app.crypto.EncryptionService
import com.app.transport.MeshConstants
import com.app.transport.MeshTelemetry
import com.app.transport.debug.DebugSettingsManager
import com.app.transport.mesh.aware.WifiAwareConnectionTracker
import com.app.transport.mesh.aware.WifiAwareSupport
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
class WifiAwareBearer(
    private val context: Context,
    private val encryptionService: EncryptionService,
    debugSettingsManager: DebugSettingsManager,
    fragmentManager: FragmentManager,
    transferProgressManager: TransferProgressManager,
) : MeshBearer {

    internal val telemetry: MeshTelemetry = debugSettingsManager

    companion object {
        private const val TAG = "WifiAwareBearer"
        private val MAX_TTL: UByte = MeshConstants.MESSAGE_TTL_HOPS
        private const val SERVICE_NAME = "bitchat"
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

    internal val incomingFlow = MutableSharedFlow<RoutedPacket>(extraBufferCapacity = 64)
    override val incoming: Flow<RoutedPacket> = incomingFlow.asSharedFlow()

    internal val neighborsState = MutableStateFlow<Set<PeerLink>>(emptySet())
    override val neighbors: StateFlow<Set<PeerLink>> = neighborsState.asStateFlow()

    internal val eventsFlow = MutableSharedFlow<BearerEvent>(extraBufferCapacity = 64)
    override val events: Flow<BearerEvent> = eventsFlow.asSharedFlow()

    override fun bindPeer(peerID: String, linkAddress: String) {
        // Ownership rule: this bearer only tracks aware:* pseudo-addresses.
        if (!linkAddress.startsWith(LINK_PREFIX)) return
        neighborsState.update { links ->
            links.map { if (it.deviceAddress == linkAddress) it.copy(peerID = peerID) else it }.toSet()
        }
    }

    // -----------------------------------------------------------------
    // Bearer state
    // -----------------------------------------------------------------

    internal val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    internal val listenerExec = Executors.newCachedThreadPool()
    private val fragmentingSender =
        FragmentingPacketSender(scope, fragmentManager, transferProgressManager, TAG)

    @Volatile internal var myPeerID: String = ""
        private set

    private val awareManager get() = WifiAwareSupport.getManager(context)
    internal val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    internal val connectionTracker = WifiAwareConnectionTracker(scope, cm)

    @Volatile private var wifiAwareSession: WifiAwareSession? = null
    @Volatile private var publishSession: PublishDiscoverySession? = null
    @Volatile internal var subscribeSession: SubscribeDiscoverySession? = null
        private set

    /** User-level intent: start() was called and stop() has not been. */
    @Volatile private var started = false
    /** Radio-level state: an attach cycle is live. */
    @Volatile private var isActive = false
    /** Radio-level activity gate for the data-path receive loops. */
    internal val isRadioActive: Boolean get() = isActive
    @Volatile private var recoveryInProgress = false
    private val sessionGeneration = AtomicInteger(0)
    private val restartPending = AtomicBoolean(false)

    internal val handleToPeerId = ConcurrentHashMap<PeerHandle, String>() // discovery mapping
    internal val discoveredTimestamps = ConcurrentHashMap<String, Long>() // peerID -> last seen time
    // Subscribe-session-scoped handles only. PeerHandles are session-scoped, so a handle obtained
    // from the publish session is NOT valid for subscribeSession.sendMessage(). Maintenance re-pings
    // (subscriber -> publisher) must use a handle that originated from the subscribe session.
    internal val subscribeHandles = ConcurrentHashMap<String, PeerHandle>()
    internal val publishHandles = ConcurrentHashMap<String, PeerHandle>()
    private val forcedServerPeers = ConcurrentHashMap.newKeySet<String>()
    private val forcedClientPeers = ConcurrentHashMap.newKeySet<String>()
    internal val clientSocketFailures = ConcurrentHashMap<String, AtomicInteger>()
    private val lastDiscoveryActivityAt = AtomicLong(0L)
    private val lastDiscoveryRefreshAt = AtomicLong(0L)

    internal fun linkAddressFor(peerId: String): String = LINK_PREFIX + peerId

    // NDP/TCP data-path layer (server offer, client connect, keep-alives, receive loop)
    private val dataPath = WifiAwareDataPath(this)

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
        neighborsState.value = emptySet()
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
                    publishSession?.let { dataPath.handleSubscriberPing(it, peerHandle) }
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
                    dataPath.handleServerReady(peerHandle, message)
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
        neighborsState.value = emptySet()
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

    internal fun rememberDiscoveredPeer(peerId: String) {
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
        dataPath.handleSubscriberPing(pubSession, peerHandle)
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

    internal fun requestRoleReversal(peerId: String, allowForcedClientOverride: Boolean = false) {
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

    internal fun shouldRequestRoleReversalAfterClientFailure(peerId: String): Boolean {
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
     * Determines whether this device should act as the server in a given peer relationship.
     */
    internal fun amIServerFor(peerId: String): Boolean = when {
        forcedClientPeers.contains(peerId) -> false
        forcedServerPeers.contains(peerId) -> true
        else -> myPeerID < peerId
    }

    fun debugInfo(): String = buildString {
        appendLine("=== Wi-Fi Aware Bearer ===")
        appendLine("started=$started active=$isActive generation=${sessionGeneration.get()}")
        appendLine("myPeerID=$myPeerID")
        append(connectionTracker.getDebugInfo())
    }
}
