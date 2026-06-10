package com.app.data.routing

import com.app.transport.mesh.MeshNetwork
import com.app.transport.mesh.BluetoothMeshService
import com.app.transport.routing.NostrIdentityProvider
import com.app.transport.routing.OutgoingEnvelope
import com.app.transport.routing.Reachability
import com.app.transport.routing.RouteStrategy
import com.app.transport.routing.SendOutcome
import dev.zacsweers.metro.Inject

/**
 * Routes messages directly over the BLE mesh when the peer has an active Noise session.
 * Priority 100 — preferred over Nostr relay.
 *
 * §6 Bearer SPI / Tier-2 RouteStrategy. Multibound into Set<RouteStrategy> via
 * @Binds @IntoSet in [com.app.data.di.DataBindings] (the impl stays internal — DIP);
 * adding a strategy never touches [RouteSelector] (OCP).
 */
@Inject
internal class MeshRouteStrategy(
    private val mesh: BluetoothMeshService,
    private val meshNetwork: MeshNetwork,
    private val nostrIdentityProvider: NostrIdentityProvider,
) : RouteStrategy {

    override val priority = 100

    /**
     * A peer is directly reachable when at least one bearer lists it in its neighbors
     * AND a Noise session has been established.
     *
     * Falls back to BMS.getPeerInfo().isConnected for the brief window between BLE connection
     * and the first announce that populates MeshBearer.neighbors.
     */
    override fun reachability(peerID: String): Reachability {
        val connected = meshNetwork.allNeighbors.any { it.peerID == peerID }
            || mesh.getPeerInfo(peerID)?.isConnected == true
        return if (connected && mesh.hasEstablishedSession(peerID))
            Reachability.Direct
        else
            Reachability.Unreachable
    }

    override suspend fun send(envelope: OutgoingEnvelope): SendOutcome {
        return when (envelope) {
            is OutgoingEnvelope.Private -> {
                mesh.sendPrivateMessage(
                    envelope.content,
                    envelope.peerID,
                    envelope.recipientNickname,
                    envelope.messageId,
                )
                // BMS schedules the send on its own scope — delivery not observable here
                SendOutcome.Accepted
            }
            is OutgoingEnvelope.Receipt -> {
                val readerNickname = mesh.getPeerNicknames()[envelope.peerID] ?: mesh.myPeerID
                mesh.sendReadReceipt(envelope.originalMessageId, envelope.peerID, readerNickname)
                SendOutcome.Accepted
            }
            is OutgoingEnvelope.Ack -> {
                // Mesh delivery ACKs are sent automatically by the receiver on the protocol level;
                // nothing to dispatch here — trivially complete.
                SendOutcome.Sent
            }
            is OutgoingEnvelope.Favorite -> {
                val myNpub = try { nostrIdentityProvider.current()?.npub } catch (_: Exception) { null }
                val content = if (envelope.isFavorite)
                    "[FAVORITED]:${myNpub ?: ""}"
                else
                    "[UNFAVORITED]:${myNpub ?: ""}"
                val nickname = mesh.getPeerNicknames()[envelope.peerID] ?: envelope.peerID
                mesh.sendPrivateMessage(content, envelope.peerID, nickname)
                SendOutcome.Accepted
            }
        }
    }
}
