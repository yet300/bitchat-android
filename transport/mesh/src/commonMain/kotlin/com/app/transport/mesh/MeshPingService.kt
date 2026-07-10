package com.app.transport.mesh

import co.touchlab.stately.collections.ConcurrentMutableMap
import com.app.common.encoding.hexEncodedString
import com.app.common.utils.Log
import com.app.transport.MeshConstants
import com.app.transport.model.MeshPingPayload
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.peerIdToRoutingBytes
import com.app.transport.sync.SyncResponseRateLimiter
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Outcome of one directed echo probe: round-trip time, and the hop count when the TTLs allow it. */
data class MeshPingResult(val rttMs: Long, val hops: Int?)

/**
 * Mesh diagnostics: directed ping (0x26) / pong (0x27) echo probes — port of the reference iOS
 * `BLEService.sendMeshPing` / `handleMeshPing` / `handleMeshPong`.
 *
 * The probe is deliberately manual (a diagnostic call, not a keep-alive). The reference client has
 * no automatic pinger either: its only caller is the user-typed `/ping` command. Liveness on both
 * sides is carried by ANNOUNCE and by the generic last-seen refresh every accepted packet performs,
 * so an automatic pinger would add radio traffic without adding information.
 */
internal class MeshPingService(
    private val myPeerID: () -> String,
    private val sendPacket: (BitchatPacket) -> Unit,
    private val nowMillis: () -> Long = ::epochMillis,
    private val nonceGenerator: () -> ByteArray = { CryptographyRandom.Default.nextBytes(MeshPingPayload.NONCE_LENGTH) },
    private val timeoutMillis: Long = PING_TIMEOUT_MS,
    /**
     * Inbound ping budget, keyed on the ingress LINK. Pings are unsigned, so `packet.senderID` is
     * attacker-controlled: keying the budget on the claimed sender would let one connected peer
     * rotate forged sender IDs to make us emit unbounded pongs (amplification).
     */
    private val inboundLimiter: SyncResponseRateLimiter = SyncResponseRateLimiter(
        maxResponses = INBOUND_MAX_PER_LINK,
        windowMillis = INBOUND_WINDOW_MS,
    ),
) {
    companion object {
        private const val TAG = "MeshPingService"

        /** iOS TransportConfig.meshPingTimeoutSeconds. */
        const val PING_TIMEOUT_MS = 10_000L

        /** iOS TransportConfig.meshPingInboundMaxPerLink / meshPingInboundWindowSeconds. */
        const val INBOUND_MAX_PER_LINK = 5
        const val INBOUND_WINDOW_MS = 10_000L
    }

    private class PendingProbe(val peerID: String, val sentAtMillis: Long) {
        val result = CompletableDeferred<MeshPingResult>()
    }

    // nonce hex -> outstanding probe
    private val pending = ConcurrentMutableMap<String, PendingProbe>()

    /**
     * Sends a directed, unencrypted, unsigned probe to [peerID] and suspends until the matching pong
     * returns, or [timeoutMillis] elapses (then null). The probe rides the normal flood/relay path,
     * so a multi-hop peer is reachable.
     */
    suspend fun ping(peerID: String): MeshPingResult? {
        val payload = MeshPingPayload.orNull(nonceGenerator(), MeshConstants.MESSAGE_TTL_HOPS) ?: run {
            Log.e(TAG, "Nonce generator produced a bad nonce length; aborting probe")
            return null
        }
        val nonceHex = payload.nonce.hexEncodedString()
        val probe = PendingProbe(peerID = peerID, sentAtMillis = nowMillis())
        pending[nonceHex] = probe
        return try {
            sendPacket(
                BitchatPacket(
                    version = 1u,
                    type = MessageType.PING.value,
                    senderID = peerIdToRoutingBytes(myPeerID()),
                    recipientID = peerIdToRoutingBytes(peerID),
                    timestamp = nowMillis().toULong(),
                    payload = payload.encode(),
                    signature = null,
                    ttl = MeshConstants.MESSAGE_TTL_HOPS,
                )
            )
            withTimeoutOrNull(timeoutMillis) { probe.result.await() }
        } finally {
            pending.remove(nonceHex)
        }
    }

    /**
     * Answers a ping addressed to us with a pong echoing its nonce. Pings addressed elsewhere are
     * left to the generic directed-relay path and must not reach here.
     *
     * [linkKey] identifies the ingress link (the directly connected peer that delivered the frame),
     * NOT the packet's claimed sender — see [inboundLimiter]. The pong still goes to the claimed
     * sender: that is the protocol.
     */
    fun onPingReceived(routed: RoutedPacket, linkKey: String) {
        val packet = routed.packet
        val ping = MeshPingPayload.decode(packet.payload) ?: run {
            Log.d(TAG, "Malformed ping via ${linkKey.take(8)}")
            return
        }
        // Drop history for links we no longer hear from. This is the limiter's only writer and it
        // is itself budgeted, so pruning per inbound ping is cheap — and BLE addresses rotate, so
        // an unpruned key map would grow for the life of the process.
        inboundLimiter.prune(nowMillis())
        if (!inboundLimiter.shouldRespond(linkKey, nowMillis())) {
            Log.w(TAG, "Rate-limiting pings via link ${linkKey.take(8)}")
            return
        }
        // The pong carries OUR fresh origin TTL, not the ping's: the hop count it yields measures
        // the return path the pong actually travels.
        val pong = MeshPingPayload(ping.nonce, MeshConstants.MESSAGE_TTL_HOPS)
        sendPacket(
            BitchatPacket(
                version = 1u,
                type = MessageType.PONG.value,
                senderID = peerIdToRoutingBytes(myPeerID()),
                recipientID = packet.senderID,
                timestamp = nowMillis().toULong(),
                payload = pong.encode(),
                signature = null,
                ttl = MeshConstants.MESSAGE_TTL_HOPS,
            )
        )
    }

    /**
     * Resolves a pong against its outstanding probe. The unguessable echoed nonce plus the sender
     * check bind the reply to the probed peer; hops come from the pong's TTL decrements.
     */
    fun onPongReceived(routed: RoutedPacket) {
        val packet = routed.packet
        val pong = MeshPingPayload.decode(packet.payload) ?: return
        val nonceHex = pong.nonce.hexEncodedString()
        val probe = pending[nonceHex] ?: return
        if (probe.peerID != routed.peerID) {
            Log.w(TAG, "Pong nonce matched but sender ${routed.peerID} != probed ${probe.peerID}; ignoring")
            return
        }
        pending.remove(nonceHex)
        probe.result.complete(
            MeshPingResult(
                rttMs = (nowMillis() - probe.sentAtMillis).coerceAtLeast(0L),
                hops = MeshPingPayload.hopCount(originTTL = pong.originTTL, receivedTTL = packet.ttl),
            )
        )
    }
}
