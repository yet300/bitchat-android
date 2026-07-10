package com.app.transport.mesh

import com.app.transport.model.MeshPingPayload
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.peerIdToRoutingBytes
import com.app.transport.sync.SyncResponseRateLimiter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the directed echo probe (iOS BLEService.sendMeshPing / handleMeshPing /
 * handleMeshPong). The wire bytes themselves are pinned by MeshPingPayloadGoldenTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshPingServiceTest {

    private companion object {
        const val ME = "1122334455667700"
        const val PEER = "aabbccddeeff0011"
        val NONCE = ByteArray(8) { it.toByte() }
    }

    private val sent = mutableListOf<BitchatPacket>()
    private var now = 1_000L

    private fun service(
        limiter: SyncResponseRateLimiter = SyncResponseRateLimiter(
            maxResponses = MeshPingService.INBOUND_MAX_PER_LINK,
            windowMillis = MeshPingService.INBOUND_WINDOW_MS,
        ),
    ) = MeshPingService(
        myPeerID = { ME },
        sendPacket = { sent.add(it) },
        nowMillis = { now },
        nonceGenerator = { NONCE },
        inboundLimiter = limiter,
    )

    private fun pingTo(recipient: String, sender: String, ttl: UByte = 7u) = RoutedPacket(
        packet = BitchatPacket(
            version = 1u,
            type = MessageType.PING.value,
            senderID = peerIdToRoutingBytes(sender),
            recipientID = peerIdToRoutingBytes(recipient),
            timestamp = 1uL,
            payload = MeshPingPayload(NONCE, 7u).encode(),
            signature = null,
            ttl = ttl,
        ),
        peerID = sender,
        relayAddress = "AA:BB:CC:DD:EE:FF",
    )

    private fun pongFrom(sender: String, originTTL: UByte, receivedTTL: UByte, nonce: ByteArray = NONCE) = RoutedPacket(
        packet = BitchatPacket(
            version = 1u,
            type = MessageType.PONG.value,
            senderID = peerIdToRoutingBytes(sender),
            recipientID = peerIdToRoutingBytes(ME),
            timestamp = 1uL,
            payload = MeshPingPayload(nonce, originTTL).encode(),
            signature = null,
            ttl = receivedTTL,
        ),
        peerID = sender,
    )

    @Test
    fun `ping emits a directed unsigned probe at full TTL`() = runTest {
        val svc = service()
        launch { svc.ping(PEER) }
        runCurrent()

        assertEquals(1, sent.size)
        val packet = sent.single()
        assertEquals(MessageType.PING.value, packet.type)
        assertEquals(7u.toUByte(), packet.ttl)
        assertNull("probes are unsigned", packet.signature)
        assertTrue(peerIdToRoutingBytes(PEER).contentEquals(packet.recipientID!!))
        assertTrue(peerIdToRoutingBytes(ME).contentEquals(packet.senderID))
        assertEquals(MeshPingPayload(NONCE, 7u), MeshPingPayload.decode(packet.payload))
    }

    @Test
    fun `pong resolves the probe with rtt and hop count`() = runTest {
        val svc = service()
        var result: MeshPingResult? = null
        val probe = launch { result = svc.ping(PEER) }
        runCurrent()

        now += 42
        // originTTL 7, received at TTL 5 => two relay decrements + the delivery link = 3 hops.
        svc.onPongReceived(pongFrom(PEER, originTTL = 7u, receivedTTL = 5u))
        probe.join()

        assertEquals(MeshPingResult(rttMs = 42, hops = 3), result)
    }

    @Test
    fun `probe times out when no pong arrives`() = runTest {
        val svc = service()
        var result: MeshPingResult? = MeshPingResult(1, 1)
        val probe = launch { result = svc.ping(PEER) }
        advanceTimeBy(MeshPingService.PING_TIMEOUT_MS + 1)
        probe.join()

        assertNull(result)
    }

    @Test
    fun `pong from a different peer than probed is ignored`() = runTest {
        val svc = service()
        var result: MeshPingResult? = MeshPingResult(1, 1)
        val probe = launch { result = svc.ping(PEER) }
        runCurrent()

        // Correct nonce (observed on the wire by a relay) but forged sender.
        svc.onPongReceived(pongFrom("deadbeefdeadbeef", originTTL = 7u, receivedTTL = 7u))
        advanceTimeBy(MeshPingService.PING_TIMEOUT_MS + 1)
        probe.join()

        assertNull("a pong must be bound to the probed peer", result)
    }

    @Test
    fun `pong with an unknown nonce is ignored`() = runTest {
        val svc = service()
        var result: MeshPingResult? = MeshPingResult(1, 1)
        val probe = launch { result = svc.ping(PEER) }
        runCurrent()

        svc.onPongReceived(pongFrom(PEER, originTTL = 7u, receivedTTL = 7u, nonce = ByteArray(8) { 0xFF.toByte() }))
        advanceTimeBy(MeshPingService.PING_TIMEOUT_MS + 1)
        probe.join()

        assertNull(result)
    }

    @Test
    fun `inbound ping is answered with a pong echoing the nonce back to the claimed sender`() {
        val svc = service()

        svc.onPingReceived(pingTo(recipient = ME, sender = PEER), linkKey = "AA:BB:CC:DD:EE:FF")

        val pong = sent.single()
        assertEquals(MessageType.PONG.value, pong.type)
        assertEquals(7u.toUByte(), pong.ttl)
        assertNull(pong.signature)
        assertTrue("pong is addressed to the ping's claimed sender", peerIdToRoutingBytes(PEER).contentEquals(pong.recipientID!!))
        val decoded = MeshPingPayload.decode(pong.payload)
        assertNotNull(decoded)
        assertTrue("nonce is echoed verbatim", NONCE.contentEquals(decoded!!.nonce))
        assertEquals("pong carries our own fresh origin TTL", 7u.toUByte(), decoded.originTTL)
    }

    @Test
    fun `malformed ping payload is not answered`() {
        val svc = service()
        val routed = RoutedPacket(
            packet = BitchatPacket(
                version = 1u,
                type = MessageType.PING.value,
                senderID = peerIdToRoutingBytes(PEER),
                recipientID = peerIdToRoutingBytes(ME),
                timestamp = 1uL,
                payload = ByteArray(4),
                signature = null,
                ttl = 7u,
            ),
            peerID = PEER,
        )

        svc.onPingReceived(routed, linkKey = "AA:BB:CC:DD:EE:FF")

        assertTrue(sent.isEmpty())
    }

    /**
     * Anti-amplification: the budget is per ingress LINK, not per claimed sender. A peer rotating
     * forged senderIDs over one link must not reset it.
     */
    @Test
    fun `inbound pings are rate limited per ingress link, not per claimed sender`() {
        val svc = service()

        repeat(MeshPingService.INBOUND_MAX_PER_LINK + 3) { i ->
            svc.onPingReceived(
                pingTo(recipient = ME, sender = "cafe00000000000$i"),
                linkKey = "AA:BB:CC:DD:EE:FF",
            )
        }

        assertEquals(MeshPingService.INBOUND_MAX_PER_LINK, sent.size)
    }

    @Test
    fun `a different ingress link gets its own budget`() {
        val svc = service()

        repeat(MeshPingService.INBOUND_MAX_PER_LINK + 2) {
            svc.onPingReceived(pingTo(recipient = ME, sender = PEER), linkKey = "link-a")
        }
        svc.onPingReceived(pingTo(recipient = ME, sender = PEER), linkKey = "link-b")

        assertEquals(MeshPingService.INBOUND_MAX_PER_LINK + 1, sent.size)
    }
}
