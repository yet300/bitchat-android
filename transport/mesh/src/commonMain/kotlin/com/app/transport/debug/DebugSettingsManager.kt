@file:OptIn(ExperimentalTime::class)

package com.app.transport.debug

import com.app.transport.MeshTelemetry

import co.touchlab.stately.collections.ConcurrentMutableMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.app.transport.protocol.BitchatPacket
import com.app.common.encoding.toHexString
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.math.round
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Debug settings manager for controlling debug features and collecting debug data
 */
@SingleIn(AppScope::class)
@Inject
class DebugSettingsManager(
    private val debugPreferenceManager: DebugPreferenceManager,
) : MeshTelemetry {
    // NOTE: This app-scoped singleton is referenced from the mesh layer (threaded through ctors).
    // Keep in transport.debug but avoid Compose deps.

    // Debug settings state
    private val _verboseLoggingEnabled = MutableStateFlow(false)
    val verboseLoggingEnabled: StateFlow<Boolean> = _verboseLoggingEnabled.asStateFlow()
    
    private val _gattServerEnabled = MutableStateFlow(true)
    override val gattServerEnabled: StateFlow<Boolean> = _gattServerEnabled.asStateFlow()
    
    private val _gattClientEnabled = MutableStateFlow(true)
    override val gattClientEnabled: StateFlow<Boolean> = _gattClientEnabled.asStateFlow()
    
    private val _packetRelayEnabled = MutableStateFlow(true)
    override val packetRelayEnabled: StateFlow<Boolean> = _packetRelayEnabled.asStateFlow()

    // Visibility of the debug sheet; gates heavy work
    private val _debugSheetVisible = MutableStateFlow(false)
    val debugSheetVisible: StateFlow<Boolean> = _debugSheetVisible.asStateFlow()
    fun setDebugSheetVisible(visible: Boolean) { _debugSheetVisible.value = visible }

    // Connection limit overrides (debug)
    private val _maxConnectionsOverall = MutableStateFlow(8)
    override val maxConnectionsOverall: StateFlow<Int> = _maxConnectionsOverall.asStateFlow()
    private val _maxServerConnections = MutableStateFlow(8)
    override val maxServerConnections: StateFlow<Int> = _maxServerConnections.asStateFlow()
    private val _maxClientConnections = MutableStateFlow(8)
    override val maxClientConnections: StateFlow<Int> = _maxClientConnections.asStateFlow()
    
    init {
        // Load persisted defaults (if preference manager already initialized)
        try {
            _verboseLoggingEnabled.value = debugPreferenceManager.getVerboseLogging(false)
            _gattServerEnabled.value = debugPreferenceManager.getGattServerEnabled(true)
            _gattClientEnabled.value = debugPreferenceManager.getGattClientEnabled(true)
            _packetRelayEnabled.value = debugPreferenceManager.getPacketRelayEnabled(true)
            _maxConnectionsOverall.value = debugPreferenceManager.getMaxConnectionsOverall(8)
            _maxServerConnections.value = debugPreferenceManager.getMaxConnectionsServer(8)
            _maxClientConnections.value = debugPreferenceManager.getMaxConnectionsClient(8)
        } catch (_: Exception) {
            // Preferences not ready yet; keep defaults. They will be applied on first change.
        }
    }

    // Debug data collections
    private val _debugMessages = MutableStateFlow<List<DebugMessage>>(emptyList())
    val debugMessages: StateFlow<List<DebugMessage>> = _debugMessages.asStateFlow()
    
    private val _scanResults = MutableStateFlow<List<DebugScanResult>>(emptyList())
    val scanResults: StateFlow<List<DebugScanResult>> = _scanResults.asStateFlow()
    
    private val _connectedDevices = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    val connectedDevices: StateFlow<List<ConnectedDevice>> = _connectedDevices.asStateFlow()
    
    // Packet relay statistics
    private val _relayStats = MutableStateFlow(PacketRelayStats())
    val relayStats: StateFlow<PacketRelayStats> = _relayStats.asStateFlow()

    // Timestamps to compute rolling window stats
    private val relayTimestamps = ConcurrentFifoQueue<Long>()
    // Per-device and per-peer rolling timestamps for stacked graphs
    private val perDeviceRelayTimestamps = ConcurrentMutableMap<String, ConcurrentFifoQueue<Long>>()
    private val perPeerRelayTimestamps = ConcurrentMutableMap<String, ConcurrentFifoQueue<Long>>()

    // Additional buckets to split incoming vs outgoing
    private val incomingTimestamps = ConcurrentFifoQueue<Long>()
    private val outgoingTimestamps = ConcurrentFifoQueue<Long>()
    private val perDeviceIncoming = ConcurrentMutableMap<String, ConcurrentFifoQueue<Long>>()
    private val perDeviceOutgoing = ConcurrentMutableMap<String, ConcurrentFifoQueue<Long>>()
    private val perPeerIncoming = ConcurrentMutableMap<String, ConcurrentFifoQueue<Long>>()
    private val perPeerOutgoing = ConcurrentMutableMap<String, ConcurrentFifoQueue<Long>>()

    // Expose current per-second rates (updated when logging/pruning occurs)
    private val _perDeviceLastSecond: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    val perDeviceLastSecond: StateFlow<Map<String, Int>> = _perDeviceLastSecond.asStateFlow()
    private val _perPeerLastSecond: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    val perPeerLastSecond: StateFlow<Map<String, Int>> = _perPeerLastSecond.asStateFlow()
    // New flows used by UI for incoming/outgoing stacked plots
    private val _perDeviceIncomingLastSecond: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    val perDeviceIncomingLastSecond: StateFlow<Map<String, Int>> = _perDeviceIncomingLastSecond.asStateFlow()
    private val _perDeviceOutgoingLastSecond: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    val perDeviceOutgoingLastSecond: StateFlow<Map<String, Int>> = _perDeviceOutgoingLastSecond.asStateFlow()
    private val _perPeerIncomingLastSecond: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    val perPeerIncomingLastSecond: StateFlow<Map<String, Int>> = _perPeerIncomingLastSecond.asStateFlow()
    private val _perPeerOutgoingLastSecond: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    val perPeerOutgoingLastSecond: StateFlow<Map<String, Int>> = _perPeerOutgoingLastSecond.asStateFlow()

    // Per-minute counts per key
    private val _perDeviceIncomingLastMinute: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    val perDeviceIncomingLastMinute: StateFlow<Map<String, Int>> = _perDeviceIncomingLastMinute.asStateFlow()
    private val _perDeviceOutgoingLastMinute: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    val perDeviceOutgoingLastMinute: StateFlow<Map<String, Int>> = _perDeviceOutgoingLastMinute.asStateFlow()
    private val _perPeerIncomingLastMinute: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    val perPeerIncomingLastMinute: StateFlow<Map<String, Int>> = _perPeerIncomingLastMinute.asStateFlow()
    private val _perPeerOutgoingLastMinute: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    val perPeerOutgoingLastMinute: StateFlow<Map<String, Int>> = _perPeerOutgoingLastMinute.asStateFlow()

    // Totals per key (since app start)
    private val deviceIncomingTotalsMap = mutableMapOf<String, Long>()
    private val deviceOutgoingTotalsMap = mutableMapOf<String, Long>()
    private val peerIncomingTotalsMap = mutableMapOf<String, Long>()
    private val peerOutgoingTotalsMap = mutableMapOf<String, Long>()
    private val _perDeviceIncomingTotalsFlow: MutableStateFlow<Map<String, Long>> = MutableStateFlow(emptyMap())
    val perDeviceIncomingTotal: StateFlow<Map<String, Long>> = _perDeviceIncomingTotalsFlow.asStateFlow()
    private val _perDeviceOutgoingTotalsFlow: MutableStateFlow<Map<String, Long>> = MutableStateFlow(emptyMap())
    val perDeviceOutgoingTotal: StateFlow<Map<String, Long>> = _perDeviceOutgoingTotalsFlow.asStateFlow()
    private val _perPeerIncomingTotalsFlow: MutableStateFlow<Map<String, Long>> = MutableStateFlow(emptyMap())
    val perPeerIncomingTotal: StateFlow<Map<String, Long>> = _perPeerIncomingTotalsFlow.asStateFlow()
    private val _perPeerOutgoingTotalsFlow: MutableStateFlow<Map<String, Long>> = MutableStateFlow(emptyMap())
    val perPeerOutgoingTotal: StateFlow<Map<String, Long>> = _perPeerOutgoingTotalsFlow.asStateFlow()
    
    // Internal data storage for managing debug data
    private val debugMessageQueue = ConcurrentFifoQueue<DebugMessage>()
    private val scanResultsQueue = ConcurrentFifoQueue<DebugScanResult>()
    
    private fun updateRelayStatsFromTimestamps() {
        if (!_debugSheetVisible.value) return
        val now = Clock.System.now().toEpochMilliseconds()
        // prune older than 15m
        while (true) {
            val head = relayTimestamps.peek() ?: break
            if (now - head > 15 * 60 * 1000L) {
                relayTimestamps.poll()
            } else break
        }
        // prune per-device and per-peer and compute 1s/60s rates
        fun pruneAndCount1s(map: ConcurrentMutableMap<String, ConcurrentFifoQueue<Long>>): Map<String, Int> {
            val result = mutableMapOf<String, Int>()
            val emptyKeys = mutableListOf<String>()
            // Snapshot keys to avoid mutating the map while iterating it.
            for (key in map.keys.toList()) {
                val q = map[key] ?: continue
                // prune this queue
                while (true) {
                    val ts = q.peek() ?: break
                    if (now - ts > 15 * 60 * 1000L) {
                        q.poll()
                    } else break
                }
                // count last 1s only
                val count1s = q.count { now - it <= 1_000L }
                if (q.isEmpty()) {
                    // cleanup empty queues to prevent unbounded growth
                    emptyKeys.add(key)
                }
                if (count1s > 0) result[key] = count1s
            }
            emptyKeys.forEach { map.remove(it) }
            return result
        }
        fun pruneAndCount60s(map: ConcurrentMutableMap<String, ConcurrentFifoQueue<Long>>): Map<String, Int> {
            val result = mutableMapOf<String, Int>()
            for (key in map.keys.toList()) {
                val q = map[key] ?: continue
                val count60 = q.count { now - it <= 60_000L }
                if (count60 > 0) result[key] = count60
            }
            return result
        }

        val perDevice1s = pruneAndCount1s(perDeviceRelayTimestamps)
        val perPeer1s = pruneAndCount1s(perPeerRelayTimestamps)

        _perDeviceLastSecond.value = perDevice1s
        _perPeerLastSecond.value = perPeer1s
        // Also compute incoming/outgoing per-key rates
        _perDeviceIncomingLastSecond.value = pruneAndCount1s(perDeviceIncoming)
        _perDeviceOutgoingLastSecond.value = pruneAndCount1s(perDeviceOutgoing)
        _perPeerIncomingLastSecond.value = pruneAndCount1s(perPeerIncoming)
        _perPeerOutgoingLastSecond.value = pruneAndCount1s(perPeerOutgoing)
        _perDeviceIncomingLastMinute.value = pruneAndCount60s(perDeviceIncoming)
        _perDeviceOutgoingLastMinute.value = pruneAndCount60s(perDeviceOutgoing)
        _perPeerIncomingLastMinute.value = pruneAndCount60s(perPeerIncoming)
        _perPeerOutgoingLastMinute.value = pruneAndCount60s(perPeerOutgoing)
        val last1s = relayTimestamps.count { now - it <= 1_000L }
        val last10s = relayTimestamps.count { now - it <= 10_000L }
        val last1m = relayTimestamps.count { now - it <= 60_000L }
        val last15m = relayTimestamps.size
        // And incoming/outgoing per-second counters
        val last1sIncoming = incomingTimestamps.count { now - it <= 1_000L }
        val last1sOutgoing = outgoingTimestamps.count { now - it <= 1_000L }
        val last10sIncoming = incomingTimestamps.count { now - it <= 10_000L }
        val last10sOutgoing = outgoingTimestamps.count { now - it <= 10_000L }
        val last1mIncoming = incomingTimestamps.count { now - it <= 60_000L }
        val last1mOutgoing = outgoingTimestamps.count { now - it <= 60_000L }
        val last15mIncoming = incomingTimestamps.size
        val last15mOutgoing = outgoingTimestamps.size
        val totalIncoming = _relayStats.value.totalIncomingCount
        val totalOutgoing = _relayStats.value.totalOutgoingCount
        _relayStats.value = PacketRelayStats(
            totalRelaysCount = totalIncoming + totalOutgoing,
            lastSecondRelays = last1s,
            last10SecondRelays = last10s,
            lastMinuteRelays = last1m,
            last15MinuteRelays = last15m,
            lastResetTime = _relayStats.value.lastResetTime,
            lastSecondIncoming = last1sIncoming,
            lastSecondOutgoing = last1sOutgoing,
            last10SecondIncoming = last10sIncoming,
            last10SecondOutgoing = last10sOutgoing,
            lastMinuteIncoming = last1mIncoming,
            lastMinuteOutgoing = last1mOutgoing,
            last15MinuteIncoming = last15mIncoming,
            last15MinuteOutgoing = last15mOutgoing,
            totalIncomingCount = totalIncoming,
            totalOutgoingCount = totalOutgoing
        )
    }
    
    // MARK: - Setting Controls
    
    fun setVerboseLoggingEnabled(enabled: Boolean) {
        debugPreferenceManager.setVerboseLogging(enabled)
        _verboseLoggingEnabled.value = enabled
        if (enabled) {
            addDebugMessage(DebugMessage.SystemMessage("🔊 Verbose logging enabled"))
        } else {
            addDebugMessage(DebugMessage.SystemMessage("🔇 Verbose logging disabled"))
        }
    }
    
    fun setGattServerEnabled(enabled: Boolean) {
        debugPreferenceManager.setGattServerEnabled(enabled)
        _gattServerEnabled.value = enabled
        addDebugMessage(DebugMessage.SystemMessage(
            if (enabled) "🟢 GATT Server enabled" else "🔴 GATT Server disabled"
        ))
    }
    
    fun setGattClientEnabled(enabled: Boolean) {
        debugPreferenceManager.setGattClientEnabled(enabled)
        _gattClientEnabled.value = enabled
        addDebugMessage(DebugMessage.SystemMessage(
            if (enabled) "🟢 GATT Client enabled" else "🔴 GATT Client disabled"
        ))
    }
    
    fun setPacketRelayEnabled(enabled: Boolean) {
        debugPreferenceManager.setPacketRelayEnabled(enabled)
        _packetRelayEnabled.value = enabled
        addDebugMessage(DebugMessage.SystemMessage(
            if (enabled) "📡 Packet relay enabled" else "🚫 Packet relay disabled"
        ))
    }

    fun setMaxConnectionsOverall(value: Int) {
        val clamped = value.coerceIn(1, 32)
        debugPreferenceManager.setMaxConnectionsOverall(clamped)
        _maxConnectionsOverall.value = clamped
        addDebugMessage(DebugMessage.SystemMessage("🔢 Max overall connections set to $clamped"))
    }

    fun setMaxServerConnections(value: Int) {
        val clamped = value.coerceIn(1, 32)
        debugPreferenceManager.setMaxConnectionsServer(clamped)
        _maxServerConnections.value = clamped
        addDebugMessage(DebugMessage.SystemMessage("🖥️ Max server connections set to $clamped"))
    }

    fun setMaxClientConnections(value: Int) {
        val clamped = value.coerceIn(1, 32)
        debugPreferenceManager.setMaxConnectionsClient(clamped)
        _maxClientConnections.value = clamped
        addDebugMessage(DebugMessage.SystemMessage("📱 Max client connections set to $clamped"))
    }
    
    // MARK: - Debug Data Collection
    
    fun addDebugMessage(message: DebugMessage) {
        if (!verboseLoggingEnabled.value && message !is DebugMessage.SystemMessage) {
            return // Only show system messages when verbose logging is disabled
        }
        
        debugMessageQueue.offer(message)
        
        // Keep only last 200 messages to prevent memory issues
        while (debugMessageQueue.size > 200) {
            debugMessageQueue.poll()
        }
        
        _debugMessages.value = debugMessageQueue.toList()
    }
    
    override fun addScanResult(scanResult: DebugScanResult) {
        // De-duplicate by device address; keep most recent
        if (scanResultsQueue.isNotEmpty()) {
            val toRemove = scanResultsQueue.filter { it.deviceAddress == scanResult.deviceAddress }
            toRemove.forEach { scanResultsQueue.remove(it) }
        }
        scanResultsQueue.offer(scanResult)

        // Keep only last 100 unique scan results
        while (scanResultsQueue.size > 100) {
            scanResultsQueue.poll()
        }

        _scanResults.value = scanResultsQueue.toList()
    }
    
    fun updateConnectedDevices(devices: List<ConnectedDevice>) {
        _connectedDevices.value = devices
    }
    
    fun updateRelayStats(stats: PacketRelayStats) {
        _relayStats.value = stats
    }

    // Sync/GCS settings (UI-configurable)
    private val _seenPacketCapacity = MutableStateFlow(debugPreferenceManager.getSeenPacketCapacity(500))
    val seenPacketCapacity: StateFlow<Int> = _seenPacketCapacity.asStateFlow()

    private val _gcsMaxBytes = MutableStateFlow(debugPreferenceManager.getGcsMaxFilterBytes(400))
    val gcsMaxBytes: StateFlow<Int> = _gcsMaxBytes.asStateFlow()

    private val _gcsFprPercent = MutableStateFlow(debugPreferenceManager.getGcsFprPercent(1.0))
    val gcsFprPercent: StateFlow<Double> = _gcsFprPercent.asStateFlow()

    fun setSeenPacketCapacity(value: Int) {
        val clamped = value.coerceIn(10, 1000)
        debugPreferenceManager.setSeenPacketCapacity(clamped)
        _seenPacketCapacity.value = clamped
        addDebugMessage(DebugMessage.SystemMessage("🧩 max packets per sync set to $clamped"))
    }

    fun setGcsMaxBytes(value: Int) {
        val clamped = value.coerceIn(128, 1024)
        debugPreferenceManager.setGcsMaxFilterBytes(clamped)
        _gcsMaxBytes.value = clamped
        addDebugMessage(DebugMessage.SystemMessage("🌸 max GCS filter size set to $clamped bytes"))
    }

    fun setGcsFprPercent(value: Double) {
        val clamped = value.coerceIn(0.1, 5.0)
        debugPreferenceManager.setGcsFprPercent(clamped)
        _gcsFprPercent.value = clamped
        addDebugMessage(DebugMessage.SystemMessage("🎯 GCS FPR set to ${format2dp(clamped)}%"))
    }
    
    // MARK: - Debug Message Creation Helpers
    
    override fun logPeerConnection(peerID: String, nickname: String, deviceID: String, isInbound: Boolean) {
        if (verboseLoggingEnabled.value) {
            val direction = if (isInbound) "connected to our server" else "we connected as client"
            addDebugMessage(DebugMessage.PeerEvent(
                "🔗 $nickname ($peerID) $direction via device $deviceID"
            ))
        }
    }
    
    override fun logPeerDisconnection(peerID: String, nickname: String, deviceID: String) {
        if (verboseLoggingEnabled.value) {
            addDebugMessage(DebugMessage.PeerEvent(
                "❌ $nickname ($peerID) disconnected from device $deviceID"
            ))
        }
    }
    
    override fun logIncomingPacket(senderPeerID: String, senderNickname: String?, messageType: String, viaDeviceId: String?) {
        if (verboseLoggingEnabled.value) {
            val who = if (!senderNickname.isNullOrBlank()) "$senderNickname ($senderPeerID)" else senderPeerID
            val routeInfo = if (!viaDeviceId.isNullOrBlank()) " via $viaDeviceId" else " (direct)"
            addDebugMessage(DebugMessage.PacketEvent(
                "📦 Received $messageType from $who$routeInfo"
            ))
        }
    }
    fun logPacketRelay(
        packetType: String,
        originalPeerID: String,
        originalNickname: String?,
        viaDeviceId: String?
    ) {
        // Backward-compatible simple API; delegate to detailed formatter with best effort
        logPacketRelayDetailed(
            packetType = packetType,
            senderPeerID = originalPeerID,
            senderNickname = originalNickname,
            fromPeerID = null,
            fromNickname = null,
            fromDeviceAddress = viaDeviceId,
            toPeerID = null,
            toNickname = null,
            toDeviceAddress = null,
            ttl = null,
            isRelay = true
        )
    }
    

    // New, more detailed relay logger used by the mesh/broadcaster
    override fun logPacketRelayDetailed(
        packetType: String,
        senderPeerID: String?,
        senderNickname: String?,
        fromPeerID: String?,
        fromNickname: String?,
        fromDeviceAddress: String?,
        toPeerID: String?,
        toNickname: String?,
        toDeviceAddress: String?,
        ttl: UByte?,
        isRelay: Boolean,
        packetVersion: UByte,
        routeInfo: String?
    ) {
        // Build message only if verbose logging is enabled, but always update stats
        val senderLabel = when {
            !senderNickname.isNullOrBlank() && !senderPeerID.isNullOrBlank() -> "$senderNickname ($senderPeerID)"
            !senderNickname.isNullOrBlank() -> senderNickname
            !senderPeerID.isNullOrBlank() -> senderPeerID
            else -> "unknown"
        }
        val fromName = when {
            !fromNickname.isNullOrBlank() -> fromNickname
            !fromPeerID.isNullOrBlank() -> fromPeerID
            else -> "unknown"
        }
        val toName = when {
            !toNickname.isNullOrBlank() -> toNickname
            !toPeerID.isNullOrBlank() -> toPeerID
            else -> "unknown"
        }

        val fromAddr = fromDeviceAddress ?: "?"
        val toAddr = toDeviceAddress ?: "?"
        val ttlStr = ttl?.toString() ?: "?"
        val routeStr = if (routeInfo != null) " $routeInfo" else ""

        if (verboseLoggingEnabled.value) {
            if (isRelay) {
                // Relay: show [previousPeer] -> [nextPeer]
                addDebugMessage(
                    DebugMessage.RelayEvent(
                        "♻️ Relayed v$packetVersion $packetType by $senderLabel from $fromName (${fromPeerID ?: "?"}, $fromAddr) to $toName (${toPeerID ?: "?"}, $toAddr) with TTL $ttlStr$routeStr"
                    )
                )
            } else {
                addDebugMessage(
                    DebugMessage.PacketEvent(
                        "📤 Sent v$packetVersion $packetType by $senderLabel to $toName (${toPeerID ?: "?"}, $toAddr) with TTL $ttlStr$routeStr"
                    )
                )
            }
        }

        // Do not update counters here; this path is for readable logs only.
    }

    // MARK: - Debug Events for Animation
    sealed class MeshVisualEvent {
        data class PacketActivity(val peerID: String) : MeshVisualEvent()
        data class RouteActivity(val route: List<String>) : MeshVisualEvent()
    }

    private val _meshVisualEvents = kotlinx.coroutines.flow.MutableSharedFlow<MeshVisualEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val meshVisualEvents: kotlinx.coroutines.flow.SharedFlow<MeshVisualEvent> = _meshVisualEvents.asSharedFlow()

    fun emitVisualEvent(event: MeshVisualEvent) {
        if (_debugSheetVisible.value) {
            _meshVisualEvents.tryEmit(event)
        }
    }

    // Peer nickname resolver
    private var nicknameResolver: ((String) -> String?)? = null
    override fun setNicknameResolver(resolver: (String) -> String?) { nicknameResolver = resolver }
    
    // Explicit incoming/outgoing logging to avoid double counting
    override fun logIncoming(packet: BitchatPacket, fromPeerID: String, fromNickname: String?, fromDeviceAddress: String?, myPeerID: String) {
        val packetType = packet.type.toString()
        val packetVersion = packet.version
        val route = packet.route
        val routeInfo = if (!route.isNullOrEmpty()) "routed: ${route.size} hops" else null

        if (verboseLoggingEnabled.value) {
            val resolvedNick = fromNickname ?: nicknameResolver?.invoke(fromPeerID) ?: "unknown"
            val who = if (resolvedNick != "unknown") "$resolvedNick ($fromPeerID)" else fromPeerID
            val routeStr = if (routeInfo != null) " $routeInfo" else ""
            addDebugMessage(DebugMessage.PacketEvent("📥 Incoming v$packetVersion $packetType from $who (${fromDeviceAddress ?: "?"})$routeStr"))
        }

        emitVisualEvent(MeshVisualEvent.PacketActivity(fromPeerID))
        
        if (!route.isNullOrEmpty()) {
            val fullRoute = mutableListOf<String>()
            fullRoute.add(packet.senderID.toHexString())
            route.forEach { fullRoute.add(it.toHexString()) }
            packet.recipientID?.let { fullRoute.add(it.toHexString()) }
            emitVisualEvent(MeshVisualEvent.RouteActivity(fullRoute))
        }

        val now = Clock.System.now().toEpochMilliseconds()
        val visible = _debugSheetVisible.value
        if (visible) incomingTimestamps.offer(now)
        fromDeviceAddress?.let {
            perDeviceIncoming.getOrPut(it) { ConcurrentFifoQueue() }.offer(now)
            deviceIncomingTotalsMap[it] = (deviceIncomingTotalsMap[it] ?: 0L) + 1L
            _perDeviceIncomingTotalsFlow.value = deviceIncomingTotalsMap.toMap()
        }
        
        perPeerIncoming.getOrPut(fromPeerID) { ConcurrentFifoQueue() }.offer(now)
        peerIncomingTotalsMap[fromPeerID] = (peerIncomingTotalsMap[fromPeerID] ?: 0L) + 1L
        _perPeerIncomingTotalsFlow.value = peerIncomingTotalsMap.toMap()
        
        // bump totals
        val cur = _relayStats.value
        _relayStats.value = cur.copy(
            totalIncomingCount = cur.totalIncomingCount + 1,
            totalRelaysCount = cur.totalRelaysCount + 1
        )
        if (visible) updateRelayStatsFromTimestamps()
    }

    override fun logOutgoing(packetType: String, toPeerID: String?, toNickname: String?, toDeviceAddress: String?, previousHopPeerID: String?, packetVersion: UByte, routeInfo: String?) {
        if (verboseLoggingEnabled.value) {
            val who = toNickname ?: toPeerID ?: "unknown"
            val routeStr = if (routeInfo != null) " $routeInfo" else ""
            addDebugMessage(DebugMessage.PacketEvent("📤 Outgoing v$packetVersion $packetType to $who (${toPeerID ?: "?"}, ${toDeviceAddress ?: "?"})$routeStr"))
        }
        val now = Clock.System.now().toEpochMilliseconds()
        val visible = _debugSheetVisible.value
        if (visible) outgoingTimestamps.offer(now)
        toDeviceAddress?.let {
            perDeviceOutgoing.getOrPut(it) { ConcurrentFifoQueue() }.offer(now)
            deviceOutgoingTotalsMap[it] = (deviceOutgoingTotalsMap[it] ?: 0L) + 1L
            _perDeviceOutgoingTotalsFlow.value = deviceOutgoingTotalsMap.toMap()
        }
        (toPeerID ?: previousHopPeerID)?.let {
            perPeerOutgoing.getOrPut(it) { ConcurrentFifoQueue() }.offer(now)
            peerOutgoingTotalsMap[it] = (peerOutgoingTotalsMap[it] ?: 0L) + 1L
            _perPeerOutgoingTotalsFlow.value = peerOutgoingTotalsMap.toMap()
        }
        val cur = _relayStats.value
        _relayStats.value = cur.copy(
            totalOutgoingCount = cur.totalOutgoingCount + 1,
            totalRelaysCount = cur.totalRelaysCount + 1
        )
        if (visible) updateRelayStatsFromTimestamps()
    }
    
    // MARK: - Clear Data
    
    fun clearDebugMessages() {
        debugMessageQueue.clear()
        _debugMessages.value = emptyList()
        addDebugMessage(DebugMessage.SystemMessage("🗑️ Debug messages cleared"))
    }
    
    fun clearScanResults() {
        scanResultsQueue.clear()
        _scanResults.value = emptyList()
        addDebugMessage(DebugMessage.SystemMessage("🗑️ Scan results cleared"))
    }
}

/** Two-decimal formatting for debug log strings (commonMain-safe replacement for String.format). */
private fun format2dp(value: Double): String {
    val scaled = round(value * 100).toLong()
    val negative = scaled < 0
    val abs = if (negative) -scaled else scaled
    return (if (negative) "-" else "") + "${abs / 100}." + (abs % 100).toString().padStart(2, '0')
}
