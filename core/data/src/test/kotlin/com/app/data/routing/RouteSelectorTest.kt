package com.app.data.routing

import com.app.data.favorites.FavoritesPersistenceService
import com.app.transport.routing.OutgoingEnvelope
import com.app.transport.routing.PeerKeyResolver
import com.app.transport.routing.Reachability
import com.app.transport.routing.RouteStrategy
import com.app.transport.routing.SendOutcome
import com.app.transport.routing.SessionInitiator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Routing-policy unit tests (§6 Tier-2 DoD): priority order, fallback on Failed,
 * queue + handshake when all strategies are unreachable, outbox flush on session
 * established. Everything is a port, so the fakes are plain classes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteSelectorTest {

    private class FakeStrategy(
        override val priority: Int,
        var reachable: Reachability = Reachability.Unreachable,
        var outcome: SendOutcome = SendOutcome.Accepted,
    ) : RouteStrategy {
        val sent = mutableListOf<OutgoingEnvelope>()

        override fun reachability(peerID: String): Reachability = reachable

        override suspend fun send(envelope: OutgoingEnvelope): SendOutcome {
            sent.add(envelope)
            return outcome
        }
    }

    private class RecordingInitiator : SessionInitiator {
        val handshakes = mutableListOf<String>()
        override fun initiateHandshake(peerID: String) {
            handshakes.add(peerID)
        }
    }

    private val peer = "aabbccdd11223344"
    private val privateEnvelope = OutgoingEnvelope.Private(peer, "hi", "nick", "msg-1")

    private fun outbox() = Outbox(mock<FavoritesPersistenceService>())

    private fun selector(
        strategies: Set<RouteStrategy>,
        outbox: Outbox,
        initiator: SessionInitiator = RecordingInitiator(),
        keyResolver: PeerKeyResolver = PeerKeyResolver { null },
        scope: kotlinx.coroutines.CoroutineScope,
    ) = RouteSelector(strategies, outbox, initiator, keyResolver, scope)

    @Test
    fun higherPriorityStrategyWinsWhenBothReachable() = runTest {
        val mesh = FakeStrategy(priority = 100, reachable = Reachability.Direct)
        val nostr = FakeStrategy(priority = 50, reachable = Reachability.ViaRelay)
        val selector = selector(setOf(nostr, mesh), outbox(), scope = this)

        selector.route(privateEnvelope)

        assertEquals(listOf<OutgoingEnvelope>(privateEnvelope), mesh.sent)
        assertTrue("lower-priority strategy must not be used", nostr.sent.isEmpty())
    }

    @Test
    fun failedSendFallsBackToNextStrategy() = runTest {
        val mesh = FakeStrategy(priority = 100, reachable = Reachability.Direct, outcome = SendOutcome.Failed("boom"))
        val nostr = FakeStrategy(priority = 50, reachable = Reachability.ViaRelay)
        val selector = selector(setOf(mesh, nostr), outbox(), scope = this)

        selector.route(privateEnvelope)

        assertEquals(1, mesh.sent.size)
        assertEquals("fallback strategy must receive the envelope", 1, nostr.sent.size)
    }

    @Test
    fun unreachableEverywhereQueuesAndInitiatesHandshakeForPrivate() = runTest {
        val mesh = FakeStrategy(priority = 100)
        val nostr = FakeStrategy(priority = 50)
        val box = outbox()
        val initiator = RecordingInitiator()
        val selector = selector(setOf(mesh, nostr), box, initiator, scope = this)

        selector.route(privateEnvelope)

        assertTrue(mesh.sent.isEmpty() && nostr.sent.isEmpty())
        assertEquals(listOf<OutgoingEnvelope>(privateEnvelope), box.drain(peer))
        assertEquals(listOf(peer), initiator.handshakes)
    }

    @Test
    fun nonPrivateEnvelopeQueuesWithoutHandshake() = runTest {
        val mesh = FakeStrategy(priority = 100)
        val box = outbox()
        val initiator = RecordingInitiator()
        val selector = selector(setOf(mesh), box, initiator, scope = this)

        val receipt = OutgoingEnvelope.Receipt(peer, "msg-1")
        selector.route(receipt)

        assertEquals(listOf<OutgoingEnvelope>(receipt), box.drain(peer))
        assertTrue("handshake only for Private envelopes", initiator.handshakes.isEmpty())
    }

    @Test
    fun sessionEstablishedFlushesQueuedEnvelopes() = runTest {
        val mesh = FakeStrategy(priority = 100)
        val box = outbox()
        val selector = selector(setOf(mesh), box, scope = this)

        // Queue while unreachable
        selector.route(privateEnvelope)
        assertTrue(mesh.sent.isEmpty())

        // Session comes up → flush re-routes through the now-reachable strategy
        mesh.reachable = Reachability.Direct
        selector.onSessionEstablished(peer)

        assertEquals(listOf<OutgoingEnvelope>(privateEnvelope), mesh.sent)
        assertTrue("queue must be drained", box.drain(peer).isEmpty())
    }

    @Test
    fun flushAlsoDrainsEnvelopesQueuedUnderNoiseKeyHex() = runTest {
        val mesh = FakeStrategy(priority = 100)
        val box = outbox()
        val noiseHex = "ff".repeat(32)
        val envelope = OutgoingEnvelope.Private(noiseHex, "hi", "nick", "msg-2")
        box.enqueue(envelope)

        mesh.reachable = Reachability.Direct
        val selector = selector(setOf(mesh), box, keyResolver = { noiseHex }, scope = this)
        selector.flushOutboxFor(peer)

        assertEquals(listOf<OutgoingEnvelope>(envelope), mesh.sent)
    }
}
