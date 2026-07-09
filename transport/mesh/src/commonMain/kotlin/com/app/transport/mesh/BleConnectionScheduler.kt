package com.app.transport.mesh

/**
 * commonMain port of the reference iOS `BLEConnectionScheduler` — the policy that turns "connect to
 * every peripheral we discover" into a bounded, rate-limited, RSSI-gated, back-off-aware connection
 * strategy. Pure logic (no platform types, caller passes `nowMs`), so it is host-tested and shared;
 * the iOS central role drives it (Android already has an equivalent via PowerManager + limits).
 *
 * Ported behaviors: central-link cap, a bounded candidate queue scored by RSSI/recency/failures, a
 * global connect rate-limit, connect-timeout back-off, a per-peripheral weak-link cooldown, a
 * disconnect settle window, and a dynamic RSSI threshold that relaxes while isolated and tightens
 * when saturated. Parameters come from [BleRadioConfig] (parity copy of `TransportConfig`).
 */
class BleConnectionScheduler(
    private val config: BleRadioConfig = BleRadioConfig(),
) {
    data class Candidate(
        val peripheralID: String,
        val rssi: Int,
        val isConnectable: Boolean,
        val discoveredAtMs: Long,
    )

    data class ExistingConnectionState(
        val isConnecting: Boolean,
        val isConnected: Boolean,
        val lastConnectionAttemptMs: Long?,
    )

    enum class PeripheralConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

    sealed interface DiscoveryDecision {
        data object Ignore : DiscoveryDecision
        data object Queued : DiscoveryDecision
        data class ScheduleRetry(val afterMs: Long) : DiscoveryDecision
        data object CancelStaleConnection : DiscoveryDecision
        data object ConnectNow : DiscoveryDecision
    }

    sealed interface QueueDecision {
        data object None : QueueDecision
        data class RetryAfter(val afterMs: Long) : QueueDecision
        data class Connect(val candidate: Candidate) : QueueDecision
    }

    private companion object {
        const val EXISTING_ATTEMPT_GUARD_MS = 2_000L
        const val RATE_LIMIT_PAD_MS = 50L
        const val WEAK_LINK_MIN_MS = 2_000L
        const val WEAK_LINK_MAX_MS = 15_000L
        const val SCORE_TIMEOUT_BIAS_WINDOW_MS = 60_000L
    }

    private var lastGlobalConnectAttemptMs: Long = Long.MIN_VALUE / 4
    private val candidates = ArrayList<Candidate>()
    private val failureCounts = HashMap<String, Int>()
    private val recentConnectTimeouts = HashMap<String, Long>()
    // A peer we held a link with and lost usually returns soon, so it only gets a brief rediscovery
    // ignore — not the timeout back-off/cooldown reserved for peers that never answered a connect.
    private val recentDisconnects = HashMap<String, Long>()
    private var lastIsolatedAtMs: Long? = null

    private val initialThreshold = config.rssiThresholdDefault
    var dynamicRssiThreshold: Int = config.rssiThresholdDefault
        private set

    val candidateCount: Int get() = candidates.size

    fun handleDiscovery(
        candidate: Candidate,
        connectedOrConnectingCount: Int,
        existingState: ExistingConnectionState?,
        peripheralState: PeripheralConnectionState,
        nowMs: Long,
    ): DiscoveryDecision {
        if (!candidate.isConnectable) return DiscoveryDecision.Ignore

        if (candidate.rssi <= dynamicRssiThreshold) {
            enqueue(candidate)
            return DiscoveryDecision.Queued
        }
        if (connectedOrConnectingCount >= config.maxCentralLinks) {
            enqueue(candidate)
            return DiscoveryDecision.Queued
        }
        rateLimitRetryDelay(nowMs)?.let {
            enqueue(candidate)
            return DiscoveryDecision.ScheduleRetry(it)
        }
        if (existingState != null) {
            if (existingState.isConnected || existingState.isConnecting) return DiscoveryDecision.Ignore
            val lastAttempt = existingState.lastConnectionAttemptMs
            if (lastAttempt != null && nowMs - lastAttempt < EXISTING_ATTEMPT_GUARD_MS) return DiscoveryDecision.Ignore
        }
        recentConnectTimeouts[candidate.peripheralID]?.let {
            if (nowMs - it < config.timeoutDiscoveryIgnoreMs) return DiscoveryDecision.Ignore
        }
        recentDisconnects[candidate.peripheralID]?.let {
            if (nowMs - it < config.disconnectDiscoveryIgnoreMs) return DiscoveryDecision.Ignore
        }
        return when (peripheralState) {
            PeripheralConnectionState.DISCONNECTED -> DiscoveryDecision.ConnectNow
            PeripheralConnectionState.CONNECTING, PeripheralConnectionState.CONNECTED ->
                DiscoveryDecision.CancelStaleConnection
        }
    }

    fun enqueue(candidate: Candidate) {
        val existingIndex = candidates.indexOfFirst { it.peripheralID == candidate.peripheralID }
        if (existingIndex >= 0) candidates[existingIndex] = candidate else candidates.add(candidate)
        candidates.sortWith(compareByDescending<Candidate> { it.rssi }.thenBy { it.discoveredAtMs })
        if (candidates.size > config.connectionCandidatesMax) {
            while (candidates.size > config.connectionCandidatesMax) candidates.removeAt(candidates.size - 1)
        }
    }

    fun nextCandidate(
        connectedOrConnectingCount: Int,
        isAlreadyConnectingOrConnected: (String) -> Boolean,
        nowMs: Long,
    ): QueueDecision {
        if (connectedOrConnectingCount >= config.maxCentralLinks) return QueueDecision.None
        rateLimitRetryDelay(nowMs)?.let { return QueueDecision.RetryAfter(it) }

        while (candidates.isNotEmpty()) {
            candidates.sortWith(compareByDescending { score(it, nowMs) })
            val candidate = candidates.removeAt(0)
            if (!candidate.isConnectable) continue

            weakLinkRetryDelay(candidate, nowMs)?.let {
                enqueue(candidate)
                return QueueDecision.RetryAfter(it)
            }
            disconnectSettleDelay(candidate, nowMs)?.let {
                enqueue(candidate)
                return QueueDecision.RetryAfter(it)
            }
            if (isAlreadyConnectingOrConnected(candidate.peripheralID)) continue
            return QueueDecision.Connect(candidate)
        }
        return QueueDecision.None
    }

    fun recordConnectionAttempt(nowMs: Long) { lastGlobalConnectAttemptMs = nowMs }

    fun recordConnectionSuccess(peripheralID: String) {
        failureCounts[peripheralID] = 0
        recentConnectTimeouts.remove(peripheralID)
        recentDisconnects.remove(peripheralID)
    }

    fun recordConnectionFailure(peripheralID: String) {
        failureCounts[peripheralID] = (failureCounts[peripheralID] ?: 0) + 1
    }

    fun recordDisconnectError(peripheralID: String, nowMs: Long) { recentDisconnects[peripheralID] = nowMs }

    fun recordConnectionTimeout(peripheralID: String, nowMs: Long) {
        recentConnectTimeouts[peripheralID] = nowMs
        recordConnectionFailure(peripheralID)
    }

    fun pruneConnectionTimeouts(beforeMs: Long) {
        recentConnectTimeouts.entries.removeAll { it.value < beforeMs }
        recentDisconnects.entries.removeAll { it.value < beforeMs }
    }

    fun reset() {
        lastGlobalConnectAttemptMs = Long.MIN_VALUE / 4
        candidates.clear()
        failureCounts.clear()
        recentConnectTimeouts.clear()
        recentDisconnects.clear()
        lastIsolatedAtMs = null
        dynamicRssiThreshold = initialThreshold
    }

    /** Relax the RSSI gate while isolated; tighten it when links/candidates saturate. */
    fun updateRssiThreshold(connectedCount: Int, connectedOrConnectingLinkCount: Int, nowMs: Long): Int {
        if (connectedCount == 0) {
            if (lastIsolatedAtMs == null) lastIsolatedAtMs = nowMs
            val elapsed = nowMs - (lastIsolatedAtMs ?: nowMs)
            dynamicRssiThreshold = if (elapsed > config.isolationRelaxThresholdMs) {
                config.rssiIsolatedRelaxed
            } else {
                config.rssiIsolatedBase
            }
            return dynamicRssiThreshold
        }
        lastIsolatedAtMs = null
        // Flaky links are handled per-peripheral (cooldown/ignore/score bias), never globally, so one
        // flaky distant peer can't blind us to every other edge-of-range peer.
        var threshold = config.rssiThresholdDefault
        if (connectedOrConnectingLinkCount >= config.maxCentralLinks ||
            candidates.size >= config.connectionCandidatesMax
        ) {
            threshold = config.rssiConnectedThreshold
        }
        dynamicRssiThreshold = threshold
        return threshold
    }

    private fun rateLimitRetryDelay(nowMs: Long): Long? {
        val elapsed = nowMs - lastGlobalConnectAttemptMs
        if (elapsed >= config.connectRateLimitMs) return null
        return config.connectRateLimitMs - elapsed + RATE_LIMIT_PAD_MS
    }

    private fun weakLinkRetryDelay(candidate: Candidate, nowMs: Long): Long? {
        val lastTimeout = recentConnectTimeouts[candidate.peripheralID] ?: return null
        val elapsed = nowMs - lastTimeout
        if (elapsed >= config.weakLinkCooldownMs || candidate.rssi > config.weakLinkRssiCutoff) return null
        val remaining = config.weakLinkCooldownMs - elapsed
        return remaining.coerceIn(WEAK_LINK_MIN_MS, WEAK_LINK_MAX_MS)
    }

    private fun disconnectSettleDelay(candidate: Candidate, nowMs: Long): Long? {
        val lastDisconnect = recentDisconnects[candidate.peripheralID] ?: return null
        val remaining = config.disconnectDiscoveryIgnoreMs - (nowMs - lastDisconnect)
        if (remaining <= 0) return null
        return remaining + RATE_LIMIT_PAD_MS
    }

    private fun score(candidate: Candidate, nowMs: Long): Int {
        val failures = failureCounts[candidate.peripheralID] ?: 0
        val penalty = minOf(20, 1 shl minOf(4, failures))
        val timeoutBias = recentConnectTimeouts[candidate.peripheralID]?.let {
            if (nowMs - it < SCORE_TIMEOUT_BIAS_WINDOW_MS) 10 else 0
        } ?: 0
        val base = (if (candidate.isConnectable) 1000 else 0) + (candidate.rssi + 100) * 2
        val recency = -((nowMs - candidate.discoveredAtMs) / 100).toInt()
        return base + recency - penalty - timeoutBias
    }
}
