package com.app.data.nostr

import com.app.common.utils.Log
import com.app.domain.app.AppForegroundState
import com.app.transport.nostr.NostrIdentityBridge
import com.app.transport.nostr.NostrProtocol
import com.app.transport.nostr.NostrRelayManager
import com.app.transport.nostr.RelayDirectory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * Publishes our own presence heartbeats (Nostr kind 20001) into the geohash channel we are in, so
 * other clients count us among the participants. Port of the reference iOS `GeohashPresenceService`.
 * Counterpart to the read side, which already ingests everyone else's heartbeats.
 *
 * Lifecycle is the subscription's: [start] when we enter a geohash, [stop] when we leave. While
 * backgrounded the loop keeps ticking but publishes nothing, exactly as the reference skips the
 * beat rather than tearing down its timer, so resuming is immediate.
 *
 * Privacy: heartbeats only go to coarse channels ([ALLOWED_PRECISIONS] — region/province/city).
 * Announcing presence at neighborhood, block or building precision would broadcast the user's
 * approximate street address to a public relay.
 */
@SingleIn(AppScope::class)
@Inject
class GeohashPresencePublisher(
    private val relayManager: NostrRelayManager,
    private val relayDirectory: RelayDirectory,
    private val nostrIdentityBridge: NostrIdentityBridge,
    private val appForegroundState: AppForegroundState,
    private val scope: CoroutineScope,
) {

    /**
     * Random delay in `[min, max)`. Test seam: replaced with a deterministic pick so heartbeat
     * instants are exact under virtual time.
     */
    internal var jitterMs: (Long, Long) -> Long = { min, max -> Random.nextLong(min, max) }

    /**
     * Builds and relays one heartbeat. Test seam (the reference injects `deriveIdentity`/`relaySender`
     * the same way): the real path hops to a signing dispatcher, which would escape a test's virtual
     * clock, so scheduling tests substitute a recorder here.
     */
    internal var publishHeartbeat: suspend (String) -> Unit = { geohash -> sendPresence(geohash) }

    private var job: Job? = null

    /** Restarts the heartbeat loop for [geohash]; a no-op cost when already running for it. */
    fun start(geohash: String) {
        stop()
        if (geohash.length !in ALLOWED_PRECISIONS) {
            Log.d(TAG, "Not publishing presence for #$geohash: precision ${geohash.length} is too fine")
            return
        }
        job = scope.launch { heartbeatLoop(geohash) }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * The reference schedules its next beat *before* the 2–5 s decorrelation delay, giving a 40–80 s
     * period with the publish jittered inside it; composing the two delays here yields a 42–85 s
     * period with the same property that matters — every device publishes at an independently random
     * phase, so heartbeats do not converge into synchronized bursts against the relay.
     */
    private suspend fun heartbeatLoop(geohash: String) {
        delay(ENTRY_SETTLE_MS)
        while (coroutineContext.isActive) {
            delay(jitterMs(BURST_MIN_MS, BURST_MAX_MS))
            publishIfForeground(geohash)
            delay(jitterMs(LOOP_MIN_MS, LOOP_MAX_MS))
        }
    }

    private suspend fun publishIfForeground(geohash: String) {
        if (!appForegroundState.isForeground.value) return
        try {
            publishHeartbeat(geohash)
        } catch (e: CancellationException) {
            throw e  // leaving the geohash cancels us mid-publish; that is not a failure to log
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish presence for #$geohash: ${e.message}")
        }
    }

    /** Signs a kind-20001 event with the per-geohash derived identity and relays it. */
    private suspend fun sendPresence(geohash: String) {
        val identity = nostrIdentityBridge.deriveIdentity(geohash)
        val event = NostrProtocol.createGeohashPresenceEvent(geohash, identity)
        relayManager.sendEventToGeohash(event, geohash, relayDirectory)
    }

    internal companion object {
        private const val TAG = "GeohashPresencePublisher"

        /** iOS GeohashChannelLevel precisions: region(2), province(4), city(5). */
        val ALLOWED_PRECISIONS = setOf(2, 4, 5)

        /** iOS waits 5 s after a location change so the channel state settles before announcing. */
        const val ENTRY_SETTLE_MS = 5_000L

        /** iOS burstMinDelay / burstMaxDelay. */
        const val BURST_MIN_MS = 2_000L
        const val BURST_MAX_MS = 5_000L

        /** iOS loopMinInterval / loopMaxInterval. */
        const val LOOP_MIN_MS = 40_000L
        const val LOOP_MAX_MS = 80_000L
    }
}
