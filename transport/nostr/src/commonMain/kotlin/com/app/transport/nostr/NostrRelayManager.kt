@file:OptIn(ExperimentalTime::class)

package com.app.transport.nostr

import co.touchlab.stately.collections.ConcurrentMutableMap
import co.touchlab.stately.collections.ConcurrentMutableSet
import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import com.app.common.AppDispatchers
import com.app.common.utils.Log
import com.app.common.serialization.JsonConfig
import com.app.transport.NostrConstants
import com.app.transport.net.WebSocketClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.coroutines.*
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Manages WebSocket connections to Nostr relays
 * Compatible with iOS implementation with Android-specific optimizations
 */
class NostrRelayManager internal constructor(
    private val eventDeduplicator: NostrEventDeduplicator,
    private val webSocketClientProvider: WebSocketClientProvider,
    private val dispatchers: AppDispatchers = AppDispatchers(),
) {

    companion object {
        private const val TAG = "NostrRelayManager"

        // Default relay list (same as iOS)
        private val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://relay.primal.net",
            "wss://offchain.pub",
            "wss://nostr21.com"
        )
        
        // Exponential backoff configuration (same as iOS)
        private const val INITIAL_BACKOFF_INTERVAL = NostrConstants.INITIAL_BACKOFF_INTERVAL_MS  // 1 second
        private const val MAX_BACKOFF_INTERVAL = NostrConstants.MAX_BACKOFF_INTERVAL_MS    // 5 minutes
        private const val BACKOFF_MULTIPLIER = NostrConstants.BACKOFF_MULTIPLIER
        private const val MAX_RECONNECT_ATTEMPTS = NostrConstants.MAX_RECONNECT_ATTEMPTS

        // Per-relay traffic counters (messagesSent/Received) are volatile under load: a busy geo
        // channel's EOSE backlog would otherwise re-emit the whole relay list to every StateFlow
        // subscriber on every frame. They are coalesced and published at most once per interval;
        // connect/disconnect status changes still publish immediately.
        private const val RELAY_STATS_PUBLISH_INTERVAL_MS = 1_000L
        
        // Track gift-wraps we initiated for logging. Entries are removed when a relay
        // answers OK; relays that never answer would otherwise leak entries for the
        // process lifetime, so cap the set (log-level fidelity only, no behavior impact).
        private const val MAX_PENDING_GIFT_WRAP_IDS = 1000
        private val pendingGiftWrapIDs = ConcurrentMutableSet<String>()
        
        fun registerPendingGiftWrap(id: String) {
            if (pendingGiftWrapIDs.size >= MAX_PENDING_GIFT_WRAP_IDS) pendingGiftWrapIDs.clear()
            pendingGiftWrapIDs.add(id)
        }

        fun defaultRelays(): List<String> = DEFAULT_RELAYS
    }
    
    /**
     * Relay status information
     */
    data class Relay(
        val url: String,
        var isConnected: Boolean = false,
        var lastError: Throwable? = null,
        var lastConnectedAt: Long? = null,
        var messagesSent: Int = 0,
        var messagesReceived: Int = 0,
        var reconnectAttempts: Int = 0,
        var lastDisconnectedAt: Long? = null,
        var nextReconnectTime: Long? = null
    )
    
    // Published state
    private val _relays = MutableStateFlow<List<Relay>>(emptyList())
    val relays: StateFlow<List<Relay>> = _relays.asStateFlow()
    
    private val _isConnected = MutableStateFlow<Boolean>(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    // Internal state.
    // relaysByUrl is thread-safe (mutated from geo subscriptions, handleMessage and reconnect
    // coroutines, read via snapshot). Keyed by url for O(1) lookup instead of the former O(N)
    // list scans. Relay's own mutable stat fields are still non-atomic, but they are cosmetic
    // counters — the structural map operations are what must be safe.
    private val relaysByUrl = ConcurrentMutableMap<String, Relay>()
    private val connections = ConcurrentMutableMap<String, DefaultClientWebSocketSession>()

    // Idempotent connect guard: holds urls whose WebSocket session is being opened. Prevents two
    // concurrent connectToRelay calls from opening two sessions for the same relay (the loser
    // would leak). Cleared once the session is registered in [connections] (or the attempt fails).
    private val connectingUrls = ConcurrentMutableSet<String>()

    // Coalesced relay-stats publishing (see RELAY_STATS_PUBLISH_INTERVAL_MS).
    private var relaysStatsDirty = false
    private val relaysStatsLock = Lock()

    // Events waiting for disconnected relays; drained per relay on reconnect.
    private val pendingEvents = PendingEventQueue<NostrEvent>()
    
    // Coroutine scope for background operations
    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())

    // Subscription bookkeeping/restoration/validation (shares the live connections map)
    private val registry = NostrSubscriptionRegistry(scope, connections)
    
    // ktor client for WebSocket connections (via provider to honor Tor)
    private val httpClient: HttpClient
        get() = webSocketClientProvider.webSocketClient()
    
    
    // Per-geohash relay selection
    private val geohashToRelays = ConcurrentMutableMap<String, Set<String>>() // geohash -> relay URLs

    // --- Public API for geohash-specific operation ---

    /**
     * Compute and connect to relays for a given geohash (nearest + optional defaults), cache the mapping.
     */
    fun ensureGeohashRelaysConnected(
        geohash: String,
        relayDirectory: GeohashRelaySource,
        nRelays: Int = 5,
        includeDefaults: Boolean = false
    ) {
        try {
            val nearest = relayDirectory.closestRelaysForGeohash(geohash, nRelays)
            val selected = if (includeDefaults) {
                (nearest + Companion.defaultRelays()).toSet()
            } else nearest.toSet()
            if (selected.isEmpty()) {
                Log.w(TAG, "No relays selected for geohash=$geohash")
                return
            }
            geohashToRelays[geohash] = selected
            Log.i(TAG, "🌐 Geohash $geohash using ${selected.size} relays: ${selected.joinToString()}")
            ensureConnectionsFor(selected)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure relays for $geohash: ${e.message}")
        }
    }

    /**
     * Get relays mapped to a geohash (empty list if none configured).
     */
    fun getRelaysForGeohash(geohash: String): List<String> {
        return geohashToRelays[geohash]?.toList() ?: emptyList()
    }

    /**
     * Subscribe with explicit geohash routing; ensures connections exist, then targets only those relays.
     */
    fun subscribeForGeohash(
        geohash: String,
        filter: NostrFilter,
        relayDirectory: GeohashRelaySource,
        id: String = registry.generateSubscriptionId(),
        handler: (NostrEvent) -> Unit,
        includeDefaults: Boolean = false,
        nRelays: Int = 5
    ): String {
        ensureGeohashRelaysConnected(geohash, relayDirectory, nRelays, includeDefaults)
        val relayUrls = getRelaysForGeohash(geohash)
        Log.d(TAG, "📡 Subscribing id=$id for geohash=$geohash on ${relayUrls.size} relays")
        return subscribe(
            filter = filter,
            id = id,
            handler = handler,
            targetRelayUrls = relayUrls
        ).also {
            // update origin geohash for this subscription
            registry.setOriginGeohash(it, geohash)
        }
    }

    /**
     * Send an event specifically to a geohash's relays (+ optional defaults).
     */
    fun sendEventToGeohash(
        event: NostrEvent,
        geohash: String,
        relayDirectory: GeohashRelaySource,
        includeDefaults: Boolean = false,
        nRelays: Int = 5
    ) {
        ensureGeohashRelaysConnected(geohash, relayDirectory, nRelays, includeDefaults)
        val relayUrls = getRelaysForGeohash(geohash)
        if (relayUrls.isEmpty()) {
            Log.w(TAG, "No target relays to send event for geohash=$geohash; falling back to defaults")
            sendEvent(event, Companion.defaultRelays())
            return
        }
        Log.v(TAG, "📤 Sending event kind=${event.kind} to ${relayUrls.size} relays for geohash=$geohash")
        sendEvent(event, relayUrls)
    }

    // --- Internal helpers ---

    private fun ensureConnectionsFor(relayUrls: Set<String>) {
        // Ensure relays are tracked for UI/status
        relayUrls.forEach { url -> relaysByUrl.getOrPut(url) { Relay(url) } }
        publishRelays()

        scope.launch {
            relayUrls.forEach { relayUrl ->
                launch {
                    if (!connections.containsKey(relayUrl)) {
                        connectToRelay(relayUrl)
                    }
                }
            }
        }
    }

    init {
        // Initialize with default relays - avoid static initialization order issues
        try {
            DEFAULT_RELAYS.forEach { url -> relaysByUrl.getOrPut(url) { Relay(url) } }
            publishRelays()
            updateConnectionStatus()
            Log.d(TAG, "✅ NostrRelayManager initialized with ${relaysByUrl.size} default relays")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NostrRelayManager: ${e.message}", e)
            // Initialize with empty list as fallback
            _relays.value = emptyList()
            _isConnected.value = false
        }

        // Coalesce volatile per-relay traffic stats into at most one StateFlow emission per
        // interval (connect/disconnect status still publishes immediately via publishRelays()).
        scope.launch {
            while (isActive) {
                delay(RELAY_STATS_PUBLISH_INTERVAL_MS)
                if (takeRelaysStatsDirty()) publishRelays()
            }
        }
    }
    
    /**
     * Connect to all configured relays
     */
    fun connect() {
        Log.d(TAG, "🌐 Connecting to ${relaysByUrl.size} Nostr relays")

        scope.launch {
            relaysByUrl.keys.toList().forEach { url ->
                launch {
                    connectToRelay(url)
                }
            }
        }
        
        // Start periodic subscription validation
        registry.startValidation()
    }
    
    /**
     * Disconnect from all relays
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting from all relays")
        
        // Stop subscription validation
        registry.stopValidation()
        
        connections.values.forEach { session ->
            closeSession(session, "Manual disconnect")
        }
        connections.clear()
        
        // Clear per-relay subscription tracking (active subscriptions survive for restore)
        registry.clearRelayTracking()

        updateConnectionStatus()
    }
    
    /**
     * Send an event to specified relays (or all if none specified)
     */
    fun sendEvent(event: NostrEvent, relayUrls: List<String>? = null) {
        val targetRelays = relayUrls ?: relaysByUrl.keys.toList()

        // Connected relays get the event immediately and are never queued; only relays
        // without a live connection wait in the queue until they reconnect.
        val (sendNow, dropped) = pendingEvents.submit(event, targetRelays) { connections[it] != null }
        dropped.forEach {
            Log.w(TAG, "⚠️ Pending event queue full: dropped oldest event ${it.id.take(16)}…")
        }

        scope.launch {
            sendNow.forEach { relayUrl ->
                connections[relayUrl]?.let { webSocket ->
                    sendToRelay(event, webSocket, relayUrl)
                }
            }
        }
    }
    
    /**
     * Subscribe to events matching a filter
     * The subscription will be automatically re-established on reconnection
     */
    fun subscribe(
        filter: NostrFilter,
        id: String = registry.generateSubscriptionId(),
        handler: (NostrEvent) -> Unit,
        targetRelayUrls: List<String>? = null
    ): String = registry.subscribe(filter, id, handler, targetRelayUrls)

    /**
     * Unsubscribe from a subscription
     */
    fun unsubscribe(id: String) = registry.unsubscribe(id)
    
    /**
     * Manually retry connection to a specific relay
     */
    fun retryConnection(relayUrl: String) {
        val relay = relaysByUrl[relayUrl] ?: return
        
        // Reset reconnection attempts
        relay.reconnectAttempts = 0
        relay.nextReconnectTime = null
        
        // Disconnect if connected
        connections[relayUrl]?.let { closeSession(it, "Manual retry") }
        connections.remove(relayUrl)
        
        // Attempt immediate reconnection
        scope.launch {
            connectToRelay(relayUrl)
        }
    }
    
    /**
     * Reset all relay connections
     * This will automatically restore all subscriptions when reconnected
     */
    fun resetAllConnections() {
        disconnect()
        
        // Reset all relay states
        relaysByUrl.values.forEach { relay ->
            relay.reconnectAttempts = 0
            relay.nextReconnectTime = null
            relay.lastError = null
        }
        
        // Reconnect - subscriptions will be automatically restored in onOpen
        connect()
    }
    
    /**
     * Force re-establishment of all subscriptions on currently connected relays
     * Useful for ensuring subscription consistency after network issues
     */
    fun reestablishAllSubscriptions() = registry.reestablishAll()
    
    /**
     * Clear all subscription tracking, message handlers, routing caches, and queued messages.
     * Intended for panic/reset flows prior to reconnecting and re-subscribing from scratch.
     */
    fun clearAllSubscriptions() {
        try {
            // Clear persistent subscription tracking
            registry.clearAll()

            // Clear routing caches (per-geohash relay selections)
            geohashToRelays.clear()

            // Clear any queued messages waiting to be sent
            pendingEvents.clear()

            Log.i(TAG, "🧹 Cleared all Nostr subscriptions and routing caches")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear subscriptions: ${e.message}")
        }
    }
    
    /**
     * Get detailed status for all relays
     */
    fun getRelayStatuses(): List<Relay> {
        return relaysByUrl.values.toList()
    }
    
    /**
     * Get event deduplication statistics
     */
    internal fun getDeduplicationStats(): DeduplicationStats {
        return eventDeduplicator.getStats()
    }
    
    /**
     * Clear the event deduplication cache (useful for testing or debugging)
     */
    fun clearDeduplicationCache() {
        eventDeduplicator.clear()
        Log.i(TAG, "🧹 Cleared event deduplication cache")
    }
    
    /**
     * Get the count of active subscriptions
     */
    fun getActiveSubscriptionCount(): Int = registry.getActiveSubscriptionCount()

    /**
     * Get information about all active subscriptions (for debugging)
     */
    internal fun getActiveSubscriptions(): Map<String, NostrSubscriptionRegistry.SubscriptionInfo> =
        registry.getActiveSubscriptions()

    /**
     * Validate subscription consistency across all relays
     * Returns a report of any inconsistencies found
     */
    internal fun validateSubscriptionConsistency(): NostrSubscriptionRegistry.ConsistencyReport =
        registry.validateConsistency()
    
    // MARK: - Private Methods
    
    /**
     * Atomically reserve the connect slot for [url]. Returns false if a live connection already
     * exists or another attempt already holds the slot — the caller must then skip. Paired with
     * [endConnectAttempt]. `connectingUrls.add` is the atomic decision point (only one concurrent
     * caller wins), giving connectToRelay putIfAbsent semantics without opening duplicate sessions.
     */
    internal fun beginConnectAttempt(url: String): Boolean =
        !connections.containsKey(url) && connectingUrls.add(url)

    internal fun endConnectAttempt(url: String) {
        connectingUrls.remove(url)
    }

    private suspend fun connectToRelay(urlString: String) {
        // Idempotent guard: skip if already connected or a concurrent attempt is in flight.
        if (!beginConnectAttempt(urlString)) {
            return
        }

        Log.v(TAG, "Attempting to connect to Nostr relay: $urlString")

        try {
            val session = httpClient.webSocketSession(urlString)
            connections[urlString] = session
            onRelayConnected(urlString, session)

            // Pump incoming frames until the session closes or fails (replaces WebSocketListener).
            scope.launch {
                try {
                    for (frame in session.incoming) {
                        if (frame is Frame.Text) {
                            handleMessage(frame.readText(), urlString)
                        }
                    }
                    Log.d(TAG, "WebSocket closed for $urlString")
                    handleDisconnection(urlString, Exception("WebSocket closed"))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "❌ WebSocket failure for $urlString: ${e.message}")
                    handleDisconnection(urlString, e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create WebSocket connection to $urlString: ${e.message}")
            handleDisconnection(urlString, e)
        } finally {
            endConnectAttempt(urlString)
        }
    }
    
    private fun sendToRelay(event: NostrEvent, webSocket: DefaultClientWebSocketSession, relayUrl: String) {
        try {
            val request = NostrRequest.Event(event)
            val message = NostrRequest.toJson(request)
            
            Log.v(TAG, "📤 Sending Nostr event (kind: ${event.kind}) to relay: $relayUrl")
            
            val success = webSocket.trySendText(message)
            if (success) {
                // Update relay stats (coalesced publish — see markRelaysStatsDirty)
                relaysByUrl[relayUrl]?.let { it.messagesSent += 1 }
                markRelaysStatsDirty()
            } else {
                Log.e(TAG, "❌ Failed to send event to $relayUrl: WebSocket send failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send event to $relayUrl: ${e.message}")
        }
    }
    
    private fun handleMessage(message: String, relayUrl: String) {
        try {
            val jsonElement = JsonConfig.json.parseToJsonElement(message)
            if (jsonElement !is JsonArray) {
                Log.w(TAG, "Received non-array message from $relayUrl")
                return
            }

            val response = NostrResponse.fromJsonArray(jsonElement)
            
            when (response) {
                is NostrResponse.Event -> {
                    // Update relay stats (coalesced publish — a geo EOSE backlog can be hundreds
                    // of events; publishing the full relay list per frame would storm the UI).
                    relaysByUrl[relayUrl]?.let { it.messagesReceived += 1 }
                    markRelaysStatsDirty()
                    
                    // CLIENT-SIDE FILTER ENFORCEMENT: Ensure this event matches the subscription's filter
                    registry.subscriptionFor(response.subscriptionId)?.let { subInfo ->
                        val matches = try { subInfo.filter.matches(response.event) } catch (e: Exception) { true }
                        if (!matches) {
                            Log.v(TAG, "🚫 Dropping event ${response.event.id.take(16)}... not matching filter for sub=${response.subscriptionId}")
                            // Do NOT call deduplicator here to allow the correct subscription to process it later
                            return
                        }
                    }
                    
                    // DEDUPLICATION: Check if we've already processed this event
                    val wasProcessed = eventDeduplicator.processEvent(response.event) { event ->
                        // Only log non-gift-wrap events to reduce noise
                        if (event.kind != NostrKind.GIFT_WRAP) {
                            val originGeo = registry.subscriptionFor(response.subscriptionId)?.originGeohash
                            if (originGeo != null) {
                                Log.v(TAG, "📥 Processing event (kind=${event.kind}) from relay=$relayUrl geo=$originGeo sub=${response.subscriptionId}")
                            } else {
                                Log.v(TAG, "📥 Processing event (kind=${event.kind}) from relay=$relayUrl sub=${response.subscriptionId}")
                            }
                        }
                        
                        // Call handler for new events only, off the main thread. A geo EOSE
                        // backlog is hundreds of events and DM handlers do NIP-44 gift-wrap
                        // decryption; running that on main janked/ANR'd. Every registered handler
                        // feeds a thread-safe repository/store or re-launches on its own scope, so
                        // UI marshaling happens downstream — none require main here.
                        val handler = registry.handlerFor(response.subscriptionId)
                        if (handler != null) {
                            scope.launch(dispatchers.default) {
                                handler(event)
                            }
                        } else {
                            Log.w(TAG, "⚠️ No handler for subscription ${response.subscriptionId}")
                        }
                    }
                    
                    if (!wasProcessed) {
                        //Log.v(TAG, "🔄 Duplicate event ${response.event.id.take(16)}... from relay: $relayUrl")
                    }
                }
                
                is NostrResponse.EndOfStoredEvents -> {
                    Log.v(TAG, "End of stored events for subscription: ${response.subscriptionId}")
                }
                
                is NostrResponse.Ok -> {
                    val wasGiftWrap = pendingGiftWrapIDs.remove(response.eventId)
                    if (response.accepted) {
                        Log.d(TAG, "✅ Event accepted id=${response.eventId.take(16)}... by relay: $relayUrl")
                    } else if (wasGiftWrap) {
                        Log.w(TAG, message)
                    } else {
                        Log.e(TAG, message)
                    }
                }
                
                is NostrResponse.Notice -> {
                    Log.i(TAG, "📢 Notice from $relayUrl: ${response.message}")
                }
                
                is NostrResponse.Unknown -> {
                    Log.v(TAG, "Unknown message type from $relayUrl: ${response.raw}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message from $relayUrl: ${e.message}")
        }
    }
    
    private fun handleDisconnection(relayUrl: String, error: Throwable) {
        connections.remove(relayUrl)
        // NOTE: Don't remove subscriptions here - keep them for restoration on reconnection
        // subscriptions.remove(relayUrl)  // REMOVED - this was causing subscription loss
        
        updateRelayStatus(relayUrl, false, error)
        
        // Check if this is a DNS error
        val errorMessage = error.message?.lowercase() ?: ""
        if (errorMessage.contains("hostname could not be found") || 
            errorMessage.contains("dns") ||
            errorMessage.contains("unable to resolve host")) {
            
            val relay = relaysByUrl[relayUrl]
            if (relay?.lastError == null) {
                Log.w(TAG, "Nostr relay DNS failure for $relayUrl - not retrying")
            }
            return
        }
        
        // Implement exponential backoff for non-DNS errors
        val relay = relaysByUrl[relayUrl] ?: return
        relay.reconnectAttempts++
        
        // Stop attempting after max attempts
        if (relay.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnection attempts ($MAX_RECONNECT_ATTEMPTS) reached for $relayUrl")
            return
        }
        
        // Calculate backoff interval
        val backoffInterval = min(
            INITIAL_BACKOFF_INTERVAL * BACKOFF_MULTIPLIER.pow(relay.reconnectAttempts - 1.0),
            MAX_BACKOFF_INTERVAL.toDouble()
        ).toLong()
        
        relay.nextReconnectTime = Clock.System.now().toEpochMilliseconds() + backoffInterval
        
        Log.d(TAG, "Scheduling reconnection to $relayUrl in ${backoffInterval / 1000}s (attempt ${relay.reconnectAttempts})")
        
        // Schedule reconnection
        scope.launch {
            delay(backoffInterval)
            connectToRelay(relayUrl)
        }
    }
    
    private fun updateRelayStatus(url: String, isConnected: Boolean, error: Throwable? = null) {
        val relay = relaysByUrl[url] ?: return

        relay.isConnected = isConnected
        relay.lastError = error
        
        if (isConnected) {
            relay.lastConnectedAt = Clock.System.now().toEpochMilliseconds()
            relay.reconnectAttempts = 0
            relay.nextReconnectTime = null
        } else {
            relay.lastDisconnectedAt = Clock.System.now().toEpochMilliseconds()
        }

        // Connect/disconnect is a status change users must see immediately — publish now.
        publishRelays()
        updateConnectionStatus()
    }

    /** Immediately publish the current relay snapshot to the StateFlow. */
    private fun publishRelays() {
        _relays.value = relaysByUrl.values.toList()
    }

    /** Flag that only cosmetic traffic counters changed; the ticker publishes within the interval. */
    internal fun markRelaysStatsDirty() = relaysStatsLock.withLock { relaysStatsDirty = true }

    /** Consume the dirty flag: returns true at most once per burst of marks (coalescing). */
    internal fun takeRelaysStatsDirty(): Boolean = relaysStatsLock.withLock {
        val dirty = relaysStatsDirty
        relaysStatsDirty = false
        dirty
    }

    private fun updateConnectionStatus() {
        val connected = relaysByUrl.values.any { it.isConnected }
        _isConnected.value = connected
    }
    
    /**
     * Handle a freshly opened relay session: mark connected, restore subscriptions, flush the queue.
     * Equivalent to the former WebSocketListener.onOpen.
     */
    private fun onRelayConnected(relayUrl: String, session: DefaultClientWebSocketSession) {
        Log.d(TAG, "✅ Connected to Nostr relay: $relayUrl")
        updateRelayStatus(relayUrl, true)

        // Restore all active subscriptions for this relay
        registry.restoreSubscriptionsForRelay(relayUrl, session)

        // Deliver any queued events that were waiting for this relay. An event submitted
        // concurrently with this drain may miss it and stay queued until the next
        // reconnect — same eventual-delivery guarantee as before, now without re-sending
        // already-delivered events.
        pendingEvents.drainFor(relayUrl).forEach { event ->
            sendToRelay(event, session, relayUrl)
        }
    }

    /**
     * Gracefully close a relay session off the caller's thread. The receive loop then ends and routes
     * through handleDisconnection (mirrors the former WebSocketListener.onClosed).
     */
    private fun closeSession(session: DefaultClientWebSocketSession, reason: String) {
        scope.launch {
            try {
                session.close(CloseReason(CloseReason.Codes.NORMAL, reason))
            } catch (_: Exception) {
            }
        }
    }
}
