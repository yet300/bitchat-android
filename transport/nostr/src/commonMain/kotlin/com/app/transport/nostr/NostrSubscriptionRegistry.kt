@file:OptIn(ExperimentalTime::class)

package com.app.transport.nostr

import co.touchlab.stately.collections.ConcurrentMutableMap
import com.app.common.utils.Log
import com.app.transport.NostrConstants
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Subscription bookkeeping side of [NostrRelayManager]: persistent subscription tracking,
 * per-relay subscription state, restoration after reconnects, and the periodic consistency
 * validation/auto-repair loop. Extracted verbatim from the manager (M4 file-size split);
 * shares the live `connections` map with it. Behavior-preserving.
 */
internal class NostrSubscriptionRegistry(
    private val scope: CoroutineScope,
    private val connections: ConcurrentMutableMap<String, DefaultClientWebSocketSession>,
) {

    companion object {
        private const val TAG = "NostrRelayManager"
        private const val SUBSCRIPTION_VALIDATION_INTERVAL = NostrConstants.SUBSCRIPTION_VALIDATION_INTERVAL_MS // 30 seconds
    }

    /**
     * Information about an active subscription that needs to be maintained across reconnections
     */
    data class SubscriptionInfo(
        val id: String,
        val filter: NostrFilter,
        val handler: (NostrEvent) -> Unit,
        val targetRelayUrls: Set<String>? = null, // null means all relays
        val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
        val originGeohash: String? = null // used for logging and grouping
    )

    data class ConsistencyReport(
        val isConsistent: Boolean,
        val inconsistencies: List<String>,
        val totalActiveSubscriptions: Int,
        val connectedRelayCount: Int
    )

    // Persistent subscription tracking for robust reconnection
    private val activeSubscriptions = ConcurrentMutableMap<String, SubscriptionInfo>() // subscription ID -> info
    private val messageHandlers = ConcurrentMutableMap<String, (NostrEvent) -> Unit>()
    private val subscriptions = ConcurrentMutableMap<String, Set<String>>() // relay URL -> subscription IDs

    // Subscription validation timer
    private var subscriptionValidationJob: Job? = null

    fun generateSubscriptionId(): String {
        return "sub-${Clock.System.now().toEpochMilliseconds()}-${Random.nextInt(1000)}"
    }

    fun subscriptionFor(id: String): SubscriptionInfo? = activeSubscriptions[id]

    fun handlerFor(id: String): ((NostrEvent) -> Unit)? = messageHandlers[id]

    fun setOriginGeohash(id: String, geohash: String) {
        activeSubscriptions[id]?.let { sub ->
            activeSubscriptions[id] = sub.copy(originGeohash = geohash)
        }
    }

    fun getActiveSubscriptionCount(): Int = activeSubscriptions.size

    fun getActiveSubscriptions(): Map<String, SubscriptionInfo> = activeSubscriptions.toMap()

    /**
     * Register a subscription and send it to the appropriate relays.
     */
    fun subscribe(
        filter: NostrFilter,
        id: String,
        handler: (NostrEvent) -> Unit,
        targetRelayUrls: List<String>? = null
    ): String {
        // Store subscription info for persistent tracking
        val subscriptionInfo = SubscriptionInfo(
            id = id,
            filter = filter,
            handler = handler,
            targetRelayUrls = targetRelayUrls?.toSet()
        )

        activeSubscriptions[id] = subscriptionInfo
        messageHandlers[id] = handler

        Log.d(TAG, "📡 Subscribing to Nostr filter id=$id ${filter.getDebugDescription()}")

        // Send subscription to appropriate relays
        sendSubscriptionToRelays(subscriptionInfo)

        return id
    }

    /**
     * Send a subscription to the appropriate relays
     */
    private fun sendSubscriptionToRelays(subscriptionInfo: SubscriptionInfo) {
        val request = NostrRequest.Subscribe(subscriptionInfo.id, listOf(subscriptionInfo.filter))
        val message = NostrRequest.toJson(request)

        // DEBUG: Log the actual serialized message format
        Log.v(TAG, "🔍 DEBUG: Serialized subscription message: $message")

        scope.launch {
            val targetRelays = subscriptionInfo.targetRelayUrls?.toList() ?: connections.keys.toList()

            targetRelays.forEach { relayUrl ->
                val webSocket = connections[relayUrl]
                if (webSocket != null) {
                    try {
                        val success = webSocket.trySendText(message)
                        if (success) {
                            // Track subscription for this relay
                            val currentSubs = subscriptions[relayUrl] ?: emptySet()
                            subscriptions[relayUrl] = currentSubs + subscriptionInfo.id

                            Log.v(TAG, "✅ Subscription '${subscriptionInfo.id}' sent to relay: $relayUrl")
                        } else {
                            Log.w(TAG, "❌ Failed to send subscription to $relayUrl: WebSocket send failed")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to send subscription to $relayUrl: ${e.message}")
                    }
                } else {
                    Log.v(TAG, "⏳ Relay $relayUrl not connected, subscription will be sent on reconnection")
                }
            }

            if (connections.isEmpty()) {
                Log.w(TAG, "⚠️ No relay connections available for subscription, will retry on reconnection")
            }
        }
    }

    /**
     * Unsubscribe from a subscription
     */
    fun unsubscribe(id: String) {
        // Remove from persistent tracking
        val subscriptionInfo = activeSubscriptions.remove(id)
        messageHandlers.remove(id)

        if (subscriptionInfo == null) {
            Log.w(TAG, "⚠️ Attempted to unsubscribe from unknown subscription: $id")
            return
        }

        Log.d(TAG, "🚫 Unsubscribing from subscription: $id")

        val request = NostrRequest.Close(id)
        val message = NostrRequest.toJson(request)

        scope.launch {
            connections.forEach { (relayUrl, webSocket) ->
                val currentSubs = subscriptions[relayUrl]
                if (currentSubs?.contains(id) == true) {
                    try {
                        webSocket.trySendText(message)
                        subscriptions[relayUrl] = currentSubs - id
                        Log.v(TAG, "Unsubscribed '$id' from relay: $relayUrl")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to unsubscribe from $relayUrl: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Force re-establishment of all subscriptions on currently connected relays
     * Useful for ensuring subscription consistency after network issues
     */
    fun reestablishAll() {
        Log.d(TAG, "🔄 Force re-establishing all ${activeSubscriptions.size} active subscriptions")

        scope.launch {
            connections.forEach { (relayUrl, webSocket) ->
                restoreSubscriptionsForRelay(relayUrl, webSocket)
            }
        }
    }

    /** Clear all subscription tracking and handlers (panic/reset flows). */
    fun clearAll() {
        activeSubscriptions.clear()
        messageHandlers.clear()
        subscriptions.clear()
    }

    /** Drop per-relay subscription tracking only (manager-level disconnect). */
    fun clearRelayTracking() {
        subscriptions.clear()
    }

    /**
     * Validate subscription consistency across all relays
     * Returns a report of any inconsistencies found
     */
    fun validateConsistency(): ConsistencyReport {
        val expectedSubs = activeSubscriptions.keys
        val actualSubsByRelay = subscriptions.toMap()
        val inconsistencies = mutableListOf<String>()

        connections.keys.forEach { relayUrl ->
            val actualSubs = actualSubsByRelay[relayUrl] ?: emptySet()
            val expectedForRelay = expectedSubs.filter { subId ->
                val subInfo = activeSubscriptions[subId]
                subInfo?.targetRelayUrls == null || subInfo.targetRelayUrls.contains(relayUrl)
            }.toSet()

            val missing = expectedForRelay - actualSubs
            val extra = actualSubs - expectedForRelay

            if (missing.isNotEmpty()) {
                inconsistencies.add("Relay $relayUrl missing subscriptions: $missing")
            }
            if (extra.isNotEmpty()) {
                inconsistencies.add("Relay $relayUrl has extra subscriptions: $extra")
            }
        }

        return ConsistencyReport(
            isConsistent = inconsistencies.isEmpty(),
            inconsistencies = inconsistencies,
            totalActiveSubscriptions = activeSubscriptions.size,
            connectedRelayCount = connections.size
        )
    }

    /**
     * Start periodic subscription validation to ensure robustness
     */
    fun startValidation() {
        stopValidation() // Stop any existing validation

        subscriptionValidationJob = scope.launch {
            while (isActive) {
                delay(SUBSCRIPTION_VALIDATION_INTERVAL)

                try {
                    val report = validateConsistency()
                    if (!report.isConsistent && report.connectedRelayCount > 0) {
                        Log.w(TAG, "⚠️ Subscription inconsistencies detected: ${report.inconsistencies}")

                        // Auto-repair: re-establish subscriptions for relays with missing ones
                        connections.forEach { (relayUrl, webSocket) ->
                            val currentSubs = subscriptions[relayUrl] ?: emptySet()
                            val expectedSubs = activeSubscriptions.keys.filter { subId ->
                                val subInfo = activeSubscriptions[subId]
                                subInfo?.targetRelayUrls == null || subInfo.targetRelayUrls.contains(relayUrl)
                            }.toSet()

                            val missingSubs = expectedSubs - currentSubs
                            if (missingSubs.isNotEmpty()) {
                                Log.i(TAG, "🔧 Auto-repairing ${missingSubs.size} missing subscriptions for $relayUrl")
                                restoreSubscriptionsForRelay(relayUrl, webSocket)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during subscription validation: ${e.message}")
                }
            }
        }

        Log.d(TAG, "🔄 Started periodic subscription validation (${SUBSCRIPTION_VALIDATION_INTERVAL / 1000}s interval)")
    }

    /**
     * Stop periodic subscription validation
     */
    fun stopValidation() {
        subscriptionValidationJob?.cancel()
        subscriptionValidationJob = null
        Log.v(TAG, "⏹️ Stopped subscription validation")
    }

    /**
     * Restore all active subscriptions for a specific relay that just reconnected
     */
    fun restoreSubscriptionsForRelay(relayUrl: String, webSocket: DefaultClientWebSocketSession) {
        val subscriptionsToRestore = activeSubscriptions.values.filter { subscriptionInfo ->
            // Include subscription if it targets all relays or specifically targets this relay
            subscriptionInfo.targetRelayUrls == null || subscriptionInfo.targetRelayUrls.contains(relayUrl)
        }

        if (subscriptionsToRestore.isEmpty()) {
            Log.v(TAG, "🔄 No subscriptions to restore for relay: $relayUrl")
            return
        }

        Log.d(TAG, "🔄 Restoring ${subscriptionsToRestore.size} subscriptions for relay: $relayUrl")

        subscriptionsToRestore.forEach { subscriptionInfo ->
            try {
                val request = NostrRequest.Subscribe(subscriptionInfo.id, listOf(subscriptionInfo.filter))
                val message = NostrRequest.toJson(request)

                val success = webSocket.trySendText(message)
                if (success) {
                    // Track subscription for this relay
                    val currentSubs = subscriptions[relayUrl] ?: emptySet()
                    subscriptions[relayUrl] = currentSubs + subscriptionInfo.id

                    Log.v(TAG, "✅ Restored subscription '${subscriptionInfo.id}' to relay: $relayUrl")
                } else {
                    Log.w(TAG, "❌ Failed to restore subscription '${subscriptionInfo.id}' to $relayUrl: WebSocket send failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to restore subscription '${subscriptionInfo.id}' to $relayUrl: ${e.message}")
            }
        }
    }
}

internal fun DefaultClientWebSocketSession.trySendText(text: String): Boolean =
    outgoing.trySend(Frame.Text(text)).isSuccess
