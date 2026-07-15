@file:OptIn(ExperimentalTime::class)

package com.app.transport.mesh

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import com.app.common.AppDispatchers
import com.app.common.utils.Log
import com.app.crypto.EncryptionService
import com.app.crypto.identity.PeerFingerprintManager
import com.app.transport.FavoriteNostrLink
import com.app.transport.GeohashReadReceiptRouter
import com.app.transport.IncomingMessageSink
import com.app.transport.MeshConstants
import com.app.transport.MeshTelemetry
import com.app.transport.NicknameSource
import com.app.transport.SeenMessageStore
import com.app.transport.VerificationService
import com.app.transport.board.BoardEventListener
import com.app.transport.courier.CourierEventListener
import com.app.transport.features.file.IncomingFileStore
import com.app.transport.group.GroupEventListener
import com.app.transport.meshgraph.MeshGraphService
import com.app.transport.model.BitchatMessage
import com.app.transport.model.PeerCapabilities
import com.app.transport.model.RoutedPacket
import com.app.transport.notification.ServiceNotifier
import com.app.transport.prekey.PrekeyEventListener
import com.app.transport.protocol.BitchatPacket
import com.app.transport.sync.GossipSyncManager
import com.app.transport.verification.VerifyEventListener
import com.app.transport.voice.VoiceFrameEventStream
import com.app.transport.vouch.VouchEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

/** Wall-clock epoch millis for wire timestamps (same semantics as System.currentTimeMillis). */
internal fun epochMillis(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * Bluetooth mesh service - REFACTORED to use component-based architecture
 * Mesh coordinator: peer lifecycle, security, relay, and multi-bearer packet flow.
 * GATT UUIDs and packet wire format aim for iOS bitchat interop (not full product parity).
 *
 * Orchestrates the following components:
 * - PeerManager: Peer lifecycle management
 * - FragmentManager: Message fragmentation and reassembly  
 * - SecurityManager: Security, duplicate detection, encryption
 * - StoreForwardManager: Offline message caching
 * - MessageHandler: Message type processing and relay logic
 * - BluetoothConnectionManager: BLE connections and GATT operations
 * - PacketProcessor: Incoming packet routing
 */
class MeshCoordinator(
    private val incomingFileStore: IncomingFileStore,
    private val debugSettingsManager: MeshTelemetry,
    private val debugPreferenceManager: com.app.transport.debug.DebugPreferenceManager,
    private val seenMessageStore: SeenMessageStore,
    private val meshGraphService: MeshGraphService,
    private val peerFingerprintManager: PeerFingerprintManager,
    // Graph-provided: the same instance backs IdentityRepositoryImpl, so Noise session
    // state lives in exactly one place.
    private val encryptionService: EncryptionService,
    // App-side wiring SPIs, implemented in :app and provided by the graph
    // (formerly mutable fields configured post-construction by MeshServiceHolder).
    private val serviceNotifier: ServiceNotifier,
    private val nicknameSource: NicknameSource,
    private val incomingSink: IncomingMessageSink,
    private val favoriteNostrLink: FavoriteNostrLink,
    private val geohashReadReceiptRouter: GeohashReadReceiptRouter,
    private val verificationService: VerificationService,
    // Graph-owned engine collaborators: the fragment reassembly state shared with the BLE
    // stack, the BLE bearer (also multibound into Set<MeshBearer>) and the bearer
    // multiplexer that is now the single data path for all mesh traffic.
    private val fragmentManager: FragmentManager,
    val bleBearer: BleBearer,
    private val meshNetwork: MeshNetwork,
    private val gatewayConfigStore: GatewayConfigStore = DisabledGatewayConfigStore,
    private val dispatchers: AppDispatchers = AppDispatchers(),
) : MeshLifecycleController, MeshService {

    companion object {
        private const val TAG = "MeshCoordinator"
        private val MAX_TTL: UByte = MeshConstants.MESSAGE_TTL_HOPS

        // Grace period between stopServices() and the actual teardown, giving the leave
        // announcement time to leave the radio. Runs on [AppDispatchers.io], so tests drive it
        // with virtual time instead of waiting in real time.
        internal const val STOP_GRACE_PERIOD_MS = 200L
    }

    // My peer identification - derived from persisted Noise identity fingerprint (first 16
    // hex chars). Re-derived by reset() after panic rotates the Noise keys; read it live,
    // do not cache.
    @Volatile
    override var myPeerID: String = encryptionService.getIdentityFingerprint().take(16)
        private set

    // Engine components. Rebuilt in place by reset()/revival — the BMS object itself keeps
    // its graph identity, so  consumers never hold a stale reference.
    private var peerManager = PeerManager(peerFingerprintManager)
    // SecurityManager is rebuilt after gossipSyncManager in wireComponents so RSR
    // solicitation can share the same RequestSyncManager instance.
    private var securityManager = SecurityManager(encryptionService, myPeerID, trafficLog = debugSettingsManager)
    private var storeForwardManager = StoreForwardManager()
    private val selfBroadcastTracker = BleSelfBroadcastTracker()
    private var messageHandler = MessageHandler(
        myPeerID,
        incomingFileStore,
        meshGraphService,
        dispatchers,
        selfBroadcastTracker,
    )

    /**
     * Narrow BLE debug surface for [com.bitchat.android.ui.debug.DebugSettingsSheet]
     * (GATT role controls, address diagnostics). Phase C removes this accessor.
     */
    override val bleDebug: BleDebugHandle get() = bleBearer
    private var packetProcessor = PacketProcessor(myPeerID, debugSettingsManager)
    private lateinit var gossipSyncManager: GossipSyncManager
    // Outbound send path for the current generation; built by wireComponents()
    private lateinit var outbound: MeshOutboundSender
    // Mesh diagnostics echo probes for the current generation; built by wireComponents()
    private lateinit var pingService: MeshPingService
    @Volatile private var gatewayEnabled = gatewayConfigStore.isGatewayEnabled()

    // Single monitor for all lifecycle transitions (start/stop/reset/finishStop): the
    // delayed teardown runs on IO while callers arrive on main — without one lock the
    // terminated/isActive/pendingStopJob handoff was only safe by call-site accident.
    private val lifecycleLock = Lock()

    // Service state management
    @Volatile
    private var isActive = false

    // Delegate for message callbacks (maintains same interface)
    var delegate: BluetoothMeshDelegate? = null

    // Narrow SPI for the Phase C app to own QR-verification orchestration without the full
    // delegate. Additive to [delegate]; both (if set) receive the verify events.
    override var verifyEventListener: VerifyEventListener? = null

    // Sink for inbound vouch batches (0x12); the platform-free coordinator attaches itself.
    override var vouchEventListener: VouchEventListener? = null

    // Sink for courier events (0x04); the platform-free courier coordinator attaches itself.
    override var courierEventListener: CourierEventListener? = null

    // Sink for group events (0x25 / 0x06 / 0x07); the platform-free group coordinator attaches itself.
    override var groupEventListener: GroupEventListener? = null

    // Sink for board events (0x23); the platform-free board coordinator attaches itself.
    override var boardEventListener: BoardEventListener? = null

    // Sink for prekey bundle events (0x24); the platform-free prekey coordinator attaches itself.
    override var prekeyEventListener: PrekeyEventListener? = null
    private var nostrCarrierHandler: ((ByteArray, String, Boolean) -> Unit)? = null
    private val voiceFrameEvents = VoiceFrameEventStream()
    private val privateVoiceFrameEvents = VoiceFrameEventStream()
    override val publicVoiceFrames = voiceFrameEvents.frames
    override val privateVoiceFrames = privateVoiceFrameEvents.frames

    // Coroutines
    private var serviceScope = CoroutineScope(dispatchers.io + SupervisorJob())
    // Tracks whether the current component generation was terminated via stopServices()
    @Volatile
    private var terminated = false
    // In-flight delayed teardown of stopServices(); completed eagerly by startServices()
    @Volatile
    private var pendingStopJob: Job? = null

    init {
        Log.i(TAG, "Initializing BluetoothMeshService for peer=$myPeerID")
        wireComponents()
    }

    /**
     * Wires the current generation of components together. Called from init and again by
     * [rebuildComponents] after reset/revival.
     */
    private fun wireComponents() {
        // Initialize sync manager (needs serviceScope)
        gossipSyncManager = GossipSyncManager(
            myPeerID = myPeerID,
            scope = serviceScope,
            trafficLog = debugSettingsManager,
            configProvider = object : GossipSyncManager.ConfigProvider {
                // 1000 matches iOS Config.seenCapacity (messages + GCS filter cap only;
                // the announce store is bounded separately by announceCapacity()).
                override fun seenCapacity(): Int = try {
                    debugPreferenceManager.getSeenPacketCapacity(1000)
                } catch (_: Exception) { 1000 }

                override fun gcsMaxBytes(): Int = try {
                    debugPreferenceManager.getGcsMaxFilterBytes(400)
                } catch (_: Exception) { 400 }

                override fun gcsTargetFpr(): Double = try {
                    debugPreferenceManager.getGcsFprPercent(1.0) / 100.0
                } catch (_: Exception) { 0.01 }
            }
        )

        // Shared RSR solicitation gate: BLE ingress + SecurityManager + gossip registry.
        val isValidRsr: (String) -> Boolean = { peerID -> gossipSyncManager.isValidSyncResponse(peerID) }
        bleBearer.isValidSyncResponse = isValidRsr
        securityManager = SecurityManager(
            encryptionService = encryptionService,
            myPeerID = myPeerID,
            trafficLog = debugSettingsManager,
            isValidSyncResponse = isValidRsr,
        )

        // Outbound send path for the current generation (signing, announces, messaging)
        outbound = MeshOutboundSender(
            myPeerID = myPeerID,
            encryptionService = encryptionService,
            meshNetwork = meshNetwork,
            meshGraphService = meshGraphService,
            peerManager = peerManager,
            gossipSyncManager = gossipSyncManager,
            nicknameSource = nicknameSource,
            seenMessageStore = seenMessageStore,
            geohashReadReceiptRouter = geohashReadReceiptRouter,
            verificationService = verificationService,
            scope = serviceScope,
            initiateHandshake = { peerID -> messageHandler.delegate?.initiateNoiseHandshake(peerID) },
            gatewayEnabled = { gatewayEnabled },
            selfBroadcastTracker = selfBroadcastTracker,
        )

        // Mesh diagnostics probes. myPeerID is read live (panic reset rotates it) and the probe
        // rides the normal flood path, so multi-hop peers answer.
        pingService = MeshPingService(
            myPeerID = { myPeerID },
            sendPacket = { packet -> meshNetwork.broadcast(RoutedPacket(packet)) },
        )

        // Wire sync manager delegate
        gossipSyncManager.delegate = object : GossipSyncManager.Delegate {
            override fun sendPacket(packet: BitchatPacket) {
                meshNetwork.broadcast(RoutedPacket(packet))
            }
            override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
                // Solicited sync traffic rides the bounded priority queue (relay/bulk),
                // never the direct synchronous write path: a diff response replaying the
                // store must not outrank interactive frames or dodge back-pressure.
                meshNetwork.sendToPeerQueued(peerID, RoutedPacket(packet))
            }
            override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket {
                return outbound.signPacketBeforeBroadcast(packet)
            }
            override fun getConnectedPeers(): List<String> =
                meshNetwork.allNeighbors.map { it.peerID }.distinct()
        }

        // Inter-component delegate wiring for the current generation
        MeshComponentWiring(
            myPeerID = myPeerID,
            scope = serviceScope,
            peerManager = peerManager,
            securityManager = securityManager,
            storeForwardManager = storeForwardManager,
            messageHandler = messageHandler,
            packetProcessor = packetProcessor,
            fragmentManager = fragmentManager,
            gossipSyncManager = gossipSyncManager,
            meshNetwork = meshNetwork,
            meshGraphService = meshGraphService,
            encryptionService = encryptionService,
            incomingSink = incomingSink,
            serviceNotifier = serviceNotifier,
            favoriteNostrLink = favoriteNostrLink,
            outbound = outbound,
            pingService = pingService,
            uiDelegate = { delegate },
            verifyListener = { verifyEventListener },
            vouchListener = { vouchEventListener },
            courierListener = { courierEventListener },
            groupListener = { groupEventListener },
            boardListener = { boardEventListener },
            prekeyListener = { prekeyEventListener },
            nostrCarrierHandler = { nostrCarrierHandler },
            voiceFrameSink = voiceFrameEvents::emit,
            privateVoiceFrameSink = privateVoiceFrameEvents::emit,
            nowMillis = { epochMillis() },
        ).wire()
        messageHandler.packetProcessor = packetProcessor
        messageHandler.favoriteNostrLink = favoriteNostrLink

        // Inject dynamic direct connection check into PeerManager
        // Matches iOS logic: a peer is direct when some bearer has a bound link for it
        peerManager.isPeerDirectlyConnected = { peerID ->
            meshNetwork.allNeighbors.any { it.peerID == peerID }
        }

        // MeshNetwork is the single data path: packets from ALL bearers feed the engine,
        // link events replace the legacy BluetoothConnectionManagerDelegate callbacks.
        // Collectors are scoped to the current generation's serviceScope and are
        // relaunched by rebuildComponents() after reset/revival.
        serviceScope.launch {
            meshNetwork.incoming.collect { routed ->
                packetProcessor.processPacket(routed)
            }
        }
        serviceScope.launch {
            meshNetwork.events.collect { event -> handleBearerEvent(event) }
        }

        Log.d(TAG, "Delegates set up; GossipSyncManager initialized")
    }

    /** Engine reaction to link-level bearer events (formerly externalDelegate callbacks). */
    private fun handleBearerEvent(event: BearerEvent) {
        when (event) {
            is BearerEvent.LinkConnected -> {
                serviceScope.launch {
                    Log.d(TAG, "Link connected: ${event.linkAddress}; scheduling announce")
                    delay(200.milliseconds)
                    sendBroadcastAnnounce()
                }
            }
            is BearerEvent.LinkDisconnected -> {
                Log.d(TAG, "Link disconnected: ${event.linkAddress}")
                try { peerManager.refreshPeerList() } catch (_: Exception) { }
            }
            is BearerEvent.RssiChanged -> {
                meshNetwork.allNeighbors.firstOrNull { it.deviceAddress == event.linkAddress }
                    ?.let { peerManager.updatePeerRSSI(it.peerID, event.rssi) }
            }
        }
    }

    /** Best-effort synchronous shutdown of the current component generation. */
    private fun stopComponentsNow() {
        try { gossipSyncManager.stop() } catch (_: Exception) { }
        try { meshNetwork.stopAll() } catch (_: Exception) { }
        try { peerManager.shutdown() } catch (_: Exception) { }
        // Graph-owned FragmentManager: clear state but keep its scope alive for revival
        try { fragmentManager.clearAllFragments() } catch (_: Exception) { }
        try { securityManager.shutdown() } catch (_: Exception) { }
        try { storeForwardManager.shutdown() } catch (_: Exception) { }
        try { messageHandler.shutdown() } catch (_: Exception) { }
        try { packetProcessor.shutdown() } catch (_: Exception) { }
        selfBroadcastTracker.clear()
    }

    /**
     * Builds a fresh component generation for the current EncryptionService identity and
     * rewires everything. Precondition: the previous generation is stopped and
     * [serviceScope] cancelled.
     */
    private fun rebuildComponents() {
        serviceScope = CoroutineScope(dispatchers.io + SupervisorJob())
        myPeerID = encryptionService.getIdentityFingerprint().take(16)
        peerManager = PeerManager(peerFingerprintManager)
        fragmentManager.clearAllFragments()
        // SecurityManager is rebuilt inside wireComponents with the new gossip RSR gate.
        storeForwardManager = StoreForwardManager()
        messageHandler = MessageHandler(
            myPeerID,
            incomingFileStore,
            meshGraphService,
            dispatchers,
            selfBroadcastTracker,
        )
        packetProcessor = PacketProcessor(myPeerID, debugSettingsManager)
        bleBearer.reset(myPeerID)
        wireComponents()
        terminated = false
    }

    /**
     * Reset-in-place for panic mode: stop everything, re-derive the peer identity from the
     * (already key-rotated) EncryptionService, rebuild the engine and restart. The graph
     * identity of this BMS object — and of [bleBearer] inside Set<MeshBearer> — never
     * changes, so no consumer is left holding a dead instance.
     */
    override fun reset() {
        lifecycleLock.withLock {
            Log.w(TAG, "🚨 Resetting mesh service in place — old peerID=$myPeerID")
            isActive = false
            stopComponentsNow()
            serviceScope.cancel() // also kills any pending stop teardown
            pendingStopJob = null
            rebuildComponents()
            startServices()
            sendBroadcastAnnounce()
            Log.w(TAG, "✅ Mesh service reset complete — new peerID=$myPeerID")
        }
    }
    
    /**
     * Start periodic debug logging every 10 seconds
     */
    private fun startPeriodicDebugLogging() {
        serviceScope.launch {
            Log.d(TAG, "Starting periodic debug logging loop")
            while (isActive) {
                try {
                    delay(10000) // 10 seconds
                    if (isActive) { // Double-check before logging
                        val debugInfo = getDebugStatus()
                        Log.d(TAG, "=== PERIODIC DEBUG STATUS ===\n$debugInfo\n=== END DEBUG STATUS ===")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic debug logging: ${e.message}")
                }
            }
            Log.d(TAG, "Periodic debug logging loop ended (isActive=$isActive)")
        }
    }

    /**
     * Send broadcast announcement every 30 seconds
     */
    private fun sendPeriodicBroadcastAnnounce() {
        serviceScope.launch {
            Log.d(TAG, "Starting periodic announce loop")
            while (isActive) {
                try {
                    delay(30000) // 30 seconds
                    sendBroadcastAnnounce()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic broadcast announce: ${e.message}")
                }
            }
            Log.d(TAG, "Periodic announce loop ended (isActive=$isActive)")
        }
    }

    /**
     * Start the mesh service
     */
    fun startServices(): Unit = lifecycleLock.withLock {
        // Prevent double starts (defensive programming)
        if (isActive) {
            Log.w(TAG, "Mesh service already active, ignoring duplicate start request")
            return
        }
        // A stop may still be inside its grace window; complete it now so its delayed
        // teardown cannot kill the generation we are about to start.
        pendingStopJob?.let { job ->
            Log.i(TAG, "Restart requested during stop grace window; completing stop first")
            job.cancel()
            finishStop(serviceScope)
        }
        if (terminated) {
            // The previous generation's scope was cancelled by stopServices(); rebuild the
            // engine in place for the same identity instead of refusing to start.
            Log.i(TAG, "Reviving terminated mesh service (in-place rebuild)")
            rebuildComponents()
        }

        Log.i(TAG, "Starting Bluetooth mesh service with peer ID: $myPeerID")

        if (meshNetwork.startAll()) {
            isActive = true

            // Start periodic announcements for peer discovery and connectivity
            sendPeriodicBroadcastAnnounce()
            Log.d(TAG, "Started periodic broadcast announcements (every 30 seconds)")
            // Start periodic syncs
            gossipSyncManager.start()
            Log.d(TAG, "GossipSyncManager started")
        } else {
            Log.e(TAG, "Failed to start any mesh bearer")
        }
    }

    /**
     * Stop all mesh services. The teardown runs after a short grace period so the leave
     * announcement gets out; a startServices() call inside that window completes the stop
     * synchronously first (see [finishStop]) instead of racing against it.
     */
    fun stopServices(): Unit = lifecycleLock.withLock {
        if (!isActive) {
            Log.w(TAG, "Mesh service not active, ignoring stop request")
            return
        }

        Log.i(TAG, "Stopping Bluetooth mesh service")
        isActive = false

        // Send leave announcement
        sendLeaveAnnouncement()

        // Capture the generation this stop belongs to: finishStop must never tear down a
        // newer generation installed by reset()/revival while the grace delay was pending.
        val stoppingScope = serviceScope
        pendingStopJob = stoppingScope.launch {
            delay(STOP_GRACE_PERIOD_MS) // Give leave message time to send
            finishStop(stoppingScope)
        }
    }

    /**
     * Completes a requested stop: shuts components down, marks the generation terminated
     * and cancels its scope. Idempotent (terminated flag) and generation-guarded
     * ([stoppingScope] identity) — invoked either by the delayed teardown coroutine or
     * eagerly by [startServices] when a restart arrives inside the grace window.
     */
    private fun finishStop(stoppingScope: CoroutineScope): Unit = lifecycleLock.withLock {
        if (terminated) return
        if (stoppingScope !== serviceScope) {
            // reset()/rebuild replaced the generation this stop belonged to; the old
            // components are already down and the new generation must not be touched.
            Log.d(TAG, "Stale stop teardown skipped (generation replaced)")
            return
        }
        Log.d(TAG, "Stopping subcomponents and cancelling scope...")
        stopComponentsNow()
        terminated = true
        pendingStopJob = null
        stoppingScope.cancel()
        Log.i(TAG, "BluetoothMeshService terminated and scope cancelled")
    }

    // Test seams for the generation guard. The race finishStop's guard protects against —
    // a delayed teardown thread already resumed from its grace delay and blocked on
    // [lifecycleLock] while reset() replaces the generation — cannot be reproduced on a
    // single-threaded virtual-time scheduler, so the lifecycle test captures the old
    // scope and replays the stale call directly.
    internal val currentGenerationScope: CoroutineScope get() = serviceScope
    internal fun simulateStaleTeardown(stoppingScope: CoroutineScope) = finishStop(stoppingScope)

    // MARK: - MeshLifecycleController (narrow contract for the foreground service)

    override fun start() = startServices()
    override fun stop() = stopServices()
    override val isMeshActive: Boolean get() = isActive
    override fun activePeerCount(): Int = getActivePeerCount()
    override fun setMeshServiceActive(active: Boolean) = bleBearer.setMeshServiceActive(active)
    override fun setAppIsActive(active: Boolean) = bleBearer.setAppIsActive(active)

    // MARK: - Outbound messaging (delegated to MeshOutboundSender)

    override fun sendMessage(content: String, mentions: List<String>, channel: String?) =
        outbound.sendMessage(content, mentions, channel)

    override fun sendMessage(content: String, mentions: List<String>, channel: String?, messageID: String) =
        outbound.sendMessage(content, mentions, channel, messageID)

    override fun sendFileBroadcast(file: com.app.transport.model.BitchatFilePacket) =
        outbound.sendFileBroadcast(file)

    override fun sendFilePrivate(recipientPeerID: String, file: com.app.transport.model.BitchatFilePacket) =
        outbound.sendFilePrivate(recipientPeerID, file)

    override fun cancelFileTransfer(transferId: String): Boolean {
        return meshNetwork.cancelTransfer(transferId)
    }

    override fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String?) =
        outbound.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)

    override fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) =
        outbound.sendReadReceipt(messageID, recipientPeerID, readerNickname)

    override fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray) =
        outbound.sendVerifyChallenge(peerID, noiseKeyHex, nonceA)

    override fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray) =
        outbound.sendVerifyResponse(peerID, noiseKeyHex, nonceA)

    override fun sendVouchAttestations(batchPayload: ByteArray, peerID: String) =
        outbound.sendVouchAttestations(batchPayload, peerID)

    override fun sendCourierEnvelope(payload: ByteArray, toPeerID: String) =
        outbound.sendCourierEnvelope(payload, toPeerID)

    override fun broadcastGroupMessage(payload: ByteArray) =
        outbound.broadcastGroupMessage(payload)

    override fun sendGroupState(payload: ByteArray, toPeerID: String, isInvite: Boolean) =
        outbound.sendGroupState(payload, toPeerID, isInvite)

    override fun sendBoardPayload(payload: ByteArray) =
        outbound.sendBoardPayload(payload)

    override fun sendPrekeyBundle(payload: ByteArray) =
        outbound.sendPrekeyBundle(payload)

    override fun sendNostrCarrier(payload: ByteArray, toPeerID: String): Boolean =
        outbound.sendNostrCarrier(payload, toPeerID)

    override fun isGatewayEnabled(): Boolean = gatewayEnabled

    override fun setGatewayEnabled(enabled: Boolean) {
        if (gatewayEnabled == enabled) return
        gatewayEnabled = enabled
        gatewayConfigStore.setGatewayEnabled(enabled)
        sendBroadcastAnnounce()
    }

    override fun setNostrCarrierHandler(handler: ((ByteArray, String, Boolean) -> Unit)?) {
        nostrCarrierHandler = handler
    }

    override fun broadcastNostrCarrier(payload: ByteArray) = outbound.broadcastNostrCarrier(payload)

    override fun broadcastVoiceFrame(payload: ByteArray) = outbound.broadcastVoiceFrame(payload)

    override fun sendVoiceFrame(payload: ByteArray, toPeerID: String) =
        outbound.sendVoiceFrame(payload, toPeerID)

    override fun connectedPeerIDs(): List<String> =
        try { peerManager.getActivePeerIDs() } catch (_: Exception) { emptyList() }

    override fun sendBroadcastAnnounce() {
        outbound.sendBroadcastAnnounce()
        // Piggyback our prekey bundle on presence like the reference (the coordinator throttles the
        // actual re-broadcast), so a peer that joins after our last publish still learns our bundle.
        try { prekeyEventListener?.onAnnounceBroadcast() } catch (_: Exception) { }
    }

    override fun sendAnnouncementToPeer(peerID: String) = outbound.sendAnnouncementToPeer(peerID)

    private fun sendLeaveAnnouncement() = outbound.sendLeaveAnnouncement()

    /**
     * Get peer nicknames
     */
    override fun getPeerNicknames(): Map<String, String> = peerManager.getAllPeerNicknames()
    
    /**
     * Get peer RSSI values  
     */
    fun getPeerRSSI(): Map<String, Int> = peerManager.getAllPeerRSSI()
    
    /**
     * Check if we have an established Noise session with a peer  
     */
    override fun hasEstablishedSession(peerID: String): Boolean {
        return encryptionService.hasEstablishedSession(peerID)
    }
    
    /**
     * Get session state for a peer (for UI state display)
     */
    fun getSessionState(peerID: String): com.app.crypto.noise.NoiseSession.NoiseSessionState {
        return encryptionService.getSessionState(peerID)
    }
    
    /**
     * Initiate Noise handshake with a specific peer (public API)
     */
    override fun initiateNoiseHandshake(peerID: String) {
        // Delegate to the existing implementation in the MessageHandler delegate
        messageHandler.delegate?.initiateNoiseHandshake(peerID)
    }
    
    /**
     * Get peer fingerprint for identity management
     */
    override fun getPeerFingerprint(peerID: String): String? {
        return peerManager.getFingerprintForPeer(peerID)
    }

    /**
     * Get current active peer count (for status/notifications)
     */
    fun getActivePeerCount(): Int {
        return try { peerManager.getActivePeerCount() } catch (_: Exception) { 0 }
    }

    /**
     * Get peer info for verification purposes
     */
    override fun getPeerInfo(peerID: String): PeerInfo? {
        return peerManager.getPeerInfo(peerID)
    }

    /**
     * Update peer information with verification data
     */
    fun updatePeerInfo(
        peerID: String,
        nickname: String,
        noisePublicKey: ByteArray,
        signingPublicKey: ByteArray,
        isVerified: Boolean,
        capabilities: PeerCapabilities = PeerCapabilities.NONE,
    ): Boolean {
        return peerManager.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified, capabilities)
    }
    
    /**
     * Get our identity fingerprint
     */
    fun getIdentityFingerprint(): String {
        return encryptionService.getIdentityFingerprint()
    }

    override fun getStaticNoisePublicKey(): ByteArray? {
        return encryptionService.getStaticPublicKey()
    }
    
    /**
     * Check if encryption icon should be shown for a peer
     */
    fun shouldShowEncryptionIcon(peerID: String): Boolean {
        return encryptionService.hasEstablishedSession(peerID)
    }
    
    
    /**
     * Get device address for a specific peer ID
     */
    fun getDeviceAddressForPeer(peerID: String): String? {
        return bleBearer.addressPeerSnapshot().entries.find { it.value == peerID }?.key
    }
    
    /**
     * Get all device addresses mapped to their peer IDs
     */
    fun getDeviceAddressToPeerMapping(): Map<String, String> {
        return bleBearer.addressPeerSnapshot()
    }
    
    /**
     * Print device addresses for all connected peers
     */
    fun printDeviceAddressesForPeers(): String {
        return peerManager.getDebugInfoWithDeviceAddresses(bleBearer.addressPeerSnapshot())
    }

    override suspend fun pingPeer(peerID: String): MeshPingResult? = pingService.ping(peerID)

    /**
     * Get debug status information
     */
    override fun getDebugStatus(): String {
        return buildString {
            appendLine("=== Bluetooth Mesh Service Debug Status ===")
            appendLine("My Peer ID: $myPeerID")
            appendLine()
            appendLine(bleBearer.debugInfo())
            appendLine()
            appendLine(peerManager.getDebugInfo(bleBearer.addressPeerSnapshot()))
            appendLine()
            appendLine(peerManager.getFingerprintDebugInfo())
            appendLine()
            appendLine(fragmentManager.getDebugInfo())
            appendLine()
            appendLine(securityManager.getDebugInfo())
            appendLine()
            appendLine(storeForwardManager.getDebugInfo())
            appendLine()
            appendLine(messageHandler.getDebugInfo())
            appendLine()
            appendLine(packetProcessor.getDebugInfo())
        }
    }
    
    // MARK: - Panic Mode Support
    
    /**
     * Clear all internal mesh service data (for panic mode)
     */
    fun clearAllInternalData() {
        Log.w(TAG, "🚨 Clearing all mesh service internal data")
        try {
            // Stop services to cease broadcasting old ID immediately
            stopServices()
            
            // Clear all managers
            fragmentManager.clearAllFragments()
            storeForwardManager.clearAllCache()
            securityManager.clearAllData()
            peerManager.clearAllPeers()
            peerManager.clearAllFingerprints()
            Log.d(TAG, "✅ Cleared all mesh service internal data")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing mesh service internal data: ${e.message}")
        }
    }
    
    /**
     * Clear all encryption and cryptographic data (for panic mode)
     */
    fun clearAllEncryptionData() {
        Log.w(TAG, "🚨 Clearing all encryption data")
        try {
            // Clear encryption service persistent identity (includes Ed25519 signing keys)
            encryptionService.clearPersistentIdentity()
            Log.d(TAG, "✅ Cleared all encryption data")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing encryption data: ${e.message}")
        }
    }
}

/**
 * Delegate interface for mesh service callbacks (maintains exact same interface)
 */
interface BluetoothMeshDelegate {
    fun didReceiveMessage(message: BitchatMessage)
    fun didUpdatePeerList(peers: List<String>)
    fun didReceiveChannelLeave(channel: String, fromPeer: String)
    fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String)
    fun didReceiveReadReceipt(messageID: String, recipientPeerID: String)
    fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long)
    fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long)
    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?
    fun getNickname(): String?
    fun isFavorite(peerID: String): Boolean
    // registerPeerPublicKey REMOVED - fingerprints now handled centrally in PeerManager
}
