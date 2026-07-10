@file:OptIn(ExperimentalCoroutinesApi::class)

package com.app.data.nostr

import com.app.domain.app.AppForegroundState
import com.app.transport.nostr.NostrEvent
import com.app.transport.nostr.NostrIdentity
import com.app.transport.nostr.NostrIdentityBridge
import com.app.transport.nostr.NostrKind
import com.app.transport.nostr.NostrRelayManager
import com.app.transport.nostr.RelayDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Lifecycle of our outgoing presence heartbeats (kind 20001), under virtual time.
 *
 * Reference: iOS `GeohashPresenceService` — randomized 40–80 s loop, 2–5 s decorrelation burst,
 * skip (not stop) while backgrounded, and never announce at fine geohash precision.
 * The event's bytes are pinned separately by GeohashPresenceEventPinTest.
 *
 * The scheduling tests replace [GeohashPresencePublisher.publishHeartbeat] with a recorder: the real
 * one signs on a background dispatcher, which would step outside the virtual clock. The default seam
 * itself is covered by [default_publish_seam_signs_and_relays_a_kind_20001_event].
 */
class GeohashPresencePublisherTest {

    private companion object {
        const val CITY = "u4pru"      // precision 5, allowed
        const val BLOCK = "u4pruyd"   // precision 7, must stay silent
        const val PRIVATE_KEY = "0000000000000000000000000000000000000000000000000000000000000001"

        /** First publish lands here when every jitter draw takes its minimum. */
        const val FIRST_BEAT_MS =
            GeohashPresencePublisher.ENTRY_SETTLE_MS + GeohashPresencePublisher.BURST_MIN_MS

        /** ...and the second one a full loop + burst later. */
        const val SECOND_BEAT_MS =
            FIRST_BEAT_MS + GeohashPresencePublisher.LOOP_MIN_MS + GeohashPresencePublisher.BURST_MIN_MS

        /** One further beat, once the loop is in steady state. */
        const val BEAT_PERIOD_MS =
            GeohashPresencePublisher.LOOP_MIN_MS + GeohashPresencePublisher.BURST_MIN_MS
    }

    private val beats = mutableListOf<String>()
    private val foreground = MutableStateFlow(true)
    private val relayManager = mock<NostrRelayManager>()
    private val relayDirectory = mock<RelayDirectory>()

    private fun publisher(scope: TestScope, identity: NostrIdentity? = null): GeohashPresencePublisher {
        val bridge = mock<NostrIdentityBridge>()
        whenever(bridge.deriveIdentity(any())).thenReturn(identity ?: NostrIdentity.fromPrivateKey(PRIVATE_KEY))

        return GeohashPresencePublisher(
            relayManager = relayManager,
            relayDirectory = relayDirectory,
            nostrIdentityBridge = bridge,
            appForegroundState = object : AppForegroundState {
                override val isForeground = foreground
            },
            scope = scope,
        ).apply {
            // Deterministic instants: always take the low end of each jitter window.
            jitterMs = { min, _ -> min }
        }
    }

    /** Scheduling-only publisher: records the geohash instead of signing and relaying. */
    private fun recordingPublisher(scope: TestScope) =
        publisher(scope).apply { publishHeartbeat = { geohash -> beats.add(geohash) } }

    @Test
    fun `entering a geohash publishes a heartbeat after the settle and burst delays`() = runTest {
        val sut = recordingPublisher(this)

        sut.start(CITY)

        advanceTimeBy(FIRST_BEAT_MS - 1)
        assertTrue("must not announce before the settle+burst delay", beats.isEmpty())

        advanceTimeBy(2)
        assertEquals(listOf(CITY), beats)

        sut.stop()
    }

    @Test
    fun `heartbeats repeat on the loop interval while we stay in the geohash`() = runTest {
        val sut = recordingPublisher(this)

        sut.start(CITY)

        advanceTimeBy(SECOND_BEAT_MS - 1)
        assertEquals(1, beats.size)

        advanceTimeBy(2)
        assertEquals(2, beats.size)

        advanceTimeBy(BEAT_PERIOD_MS)
        assertEquals(3, beats.size)

        sut.stop()
    }

    @Test
    fun `leaving the geohash stops the heartbeats`() = runTest {
        val sut = recordingPublisher(this)

        sut.start(CITY)
        advanceTimeBy(FIRST_BEAT_MS + 1)
        assertEquals(1, beats.size)

        sut.stop()
        runCurrent()

        advanceTimeBy(5 * GeohashPresencePublisher.LOOP_MAX_MS)
        assertEquals("silence after leaving the geohash", 1, beats.size)
    }

    @Test
    fun `backgrounding pauses publication and returning to the foreground resumes it`() = runTest {
        val sut = recordingPublisher(this)

        sut.start(CITY)
        advanceTimeBy(FIRST_BEAT_MS + 1)
        assertEquals(1, beats.size)

        foreground.value = false
        advanceTimeBy(3 * BEAT_PERIOD_MS)
        assertEquals("no heartbeats while backgrounded", 1, beats.size)

        foreground.value = true
        advanceTimeBy(BEAT_PERIOD_MS)
        assertEquals("heartbeats resume on foreground", 2, beats.size)

        sut.stop()
    }

    /** Privacy: presence at neighborhood/block/building precision would leak a street address. */
    @Test
    fun `no heartbeat is published for a fine-precision geohash`() = runTest {
        val sut = recordingPublisher(this)

        sut.start(BLOCK)

        advanceTimeBy(5 * GeohashPresencePublisher.LOOP_MAX_MS)
        assertTrue(beats.isEmpty())
    }

    @Test
    fun `allowed precisions match the reference region province and city levels`() {
        assertEquals(setOf(2, 4, 5), GeohashPresencePublisher.ALLOWED_PRECISIONS)
    }

    @Test
    fun `switching geohash restarts the loop for the new channel only`() = runTest {
        val sut = recordingPublisher(this)

        sut.start(CITY)
        advanceTimeBy(FIRST_BEAT_MS + 1)
        assertEquals(listOf(CITY), beats)

        sut.start("9q8yy")
        advanceTimeBy(FIRST_BEAT_MS + 1)

        assertEquals(listOf(CITY, "9q8yy"), beats)

        sut.stop()
    }

    /**
     * The seam the scheduling tests replace: exercised for real here, so the wiring from geohash to
     * a signed kind-20001 event on the geohash's relays cannot rot behind the recorder.
     */
    @Test
    fun default_publish_seam_signs_and_relays_a_kind_20001_event() = runTest {
        val sent = mutableListOf<Pair<NostrEvent, String>>()
        doAnswer { inv ->
            sent.add(inv.getArgument<NostrEvent>(0) to inv.getArgument(1))
            Unit
        }.whenever(relayManager).sendEventToGeohash(any(), any(), any(), any(), any())

        val identity = NostrIdentity.fromPrivateKey(PRIVATE_KEY)
        val sut = publisher(this, identity)

        // Call the default seam directly: it hops to a real signing dispatcher, so it is awaited
        // here rather than driven by the virtual clock.
        sut.publishHeartbeat(CITY)

        assertEquals(1, sent.size)
        val (event, geohash) = sent.single()
        assertEquals(CITY, geohash)
        assertEquals(NostrKind.GEOHASH_PRESENCE, event.kind)
        assertEquals(listOf(listOf("g", CITY)), event.tags)
        assertEquals("", event.content)
        assertEquals(identity.publicKeyHex, event.pubkey)
        assertTrue("heartbeat must be signed", event.isValidSignature())
    }
}
