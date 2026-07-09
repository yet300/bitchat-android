package com.app.transport.nostr

import com.app.common.AppDispatchers
import com.app.transport.net.WebSocketClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 (Nostr hot path) hardening regressions for [NostrRelayManager]:
 *  - S12: idempotent connect (concurrent attempts open at most one session slot),
 *  - S12: DEFAULT_RELAYS single-sourced and de-duplicated on init,
 *  - S11/S2b: relay-stats publishes are coalesced (many stat updates → one pending publish).
 *
 * The WebSocket provider is never invoked here (no network path is exercised): the guard,
 * the default-relay set, and the dirty-flag coalescing are all pure in-memory logic.
 */
class NostrRelayManagerHardeningTest {

    private fun newManager(): NostrRelayManager = NostrRelayManager(
        eventDeduplicator = NostrEventDeduplicator(),
        webSocketClientProvider = WebSocketClientProvider {
            throw UnsupportedOperationException("no network in this test")
        },
        // Real dispatchers: the init stats ticker's first tick is RELAY_STATS_PUBLISH_INTERVAL_MS
        // (1s) away, so it cannot race these sub-millisecond assertions.
        dispatchers = AppDispatchers(),
    )

    @Test
    fun `init publishes the de-duplicated default relay set`() {
        val manager = newManager()

        val published = manager.relays.value.map { it.url }
        assertEquals("no duplicate relay entries", published.size, published.toSet().size)
        assertEquals(
            "init must publish exactly the single-sourced DEFAULT_RELAYS set",
            NostrRelayManager.defaultRelays().toSet(),
            published.toSet(),
        )
    }

    @Test
    fun `beginConnectAttempt reserves a url for exactly one concurrent caller`() {
        val manager = newManager()
        val url = "wss://relay.example.test"

        val wins = runBlocking {
            List(64) { async(Dispatchers.Default) { manager.beginConnectAttempt(url) } }.awaitAll()
        }.count { it }

        assertEquals("exactly one concurrent connect attempt may reserve the slot", 1, wins)

        // Releasing the slot lets a subsequent attempt reserve again.
        manager.endConnectAttempt(url)
        assertTrue("slot must be reservable again after release", manager.beginConnectAttempt(url))
    }

    @Test
    fun `stat updates coalesce into a single pending publish`() {
        val manager = newManager()

        repeat(100) { manager.markRelaysStatsDirty() }

        assertTrue("first take after a burst of marks must be dirty", manager.takeRelaysStatsDirty())
        assertFalse("burst coalesces to a single pending publish", manager.takeRelaysStatsDirty())

        manager.markRelaysStatsDirty()
        assertTrue("a fresh mark must be observable", manager.takeRelaysStatsDirty())
    }
}
