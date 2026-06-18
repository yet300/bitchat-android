package com.app.data.routing

import android.util.Log
import com.app.domain.model.Attachment
import com.app.domain.model.ConversationId
import com.app.domain.model.GeohashChannel
import com.app.domain.model.PeerId
import com.app.domain.repository.MessageTransport
import com.app.transport.mesh.BluetoothMeshService
import com.app.transport.model.ReadReceipt
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Adapter implementing the domain [MessageTransport] port over the existing concrete routing
 * ([MessageRouter] + [BluetoothMeshService]). It hides the mesh<->Nostr choice and the addressing
 * (domain [PeerId] -> String peerID) from the domain. Replacing the if/else routing with a tested
 * policy (the two-tier SPI) is a later phase; this is the "as is" wrapper.
 */
@SingleIn(AppScope::class)
@Inject
internal class RoutingMessageTransport(
    private val mesh: BluetoothMeshService,
    private val router: MessageRouter,
) : MessageTransport {

    override suspend fun sendPublic(content: String, mentions: List<String>, channel: String?) {
        mesh.sendMessage(content, mentions, channel)
    }

    override suspend fun sendPrivate(content: String, to: PeerId, recipientNickname: String?, messageId: String) {
        router.sendPrivate(content, to.raw, recipientNickname ?: "", messageId)
    }

    override suspend fun sendGeohash(content: String, channel: GeohashChannel, nickname: String?) {
        // The whole geohash chat subsystem (subscribe + ingest incoming, presence, public posting,
        // per-geohash identity, local echo) is not yet ported to the data layer — MessageRepository
        // also stubs the geo timeline. Its legacy orchestration (GeohashViewModel/GeohashMessageHandler)
        // was removed in the Phase D cleanup without a replacement. Until a GeohashRepository lands,
        // no-op instead of throwing: the new chat UI reaches this port and must NOT crash the app.
        // TRACKING: geohash data-layer port.
        Log.w(
            "RoutingMessageTransport",
            "Dropping geohash post to ${channel.geohash}: geohash chat is not yet wired through the data layer",
        )
    }

    override suspend fun sendAttachment(attachment: Attachment, target: ConversationId, messageId: String) {
        // TRACKING(Phase B / data-media): media attachment orchestration (Attachment ->
        // BitchatFilePacket, broadcast vs private) still lives in :app (MediaSendingManager).
        throw UnsupportedOperationException(
            "Attachment sending is not yet routed through the data layer (lives in :app MediaSendingManager)",
        )
    }

    override suspend fun cancelTransfer(messageId: String): Boolean =
        mesh.cancelFileTransfer(messageId)

    override suspend fun sendReadReceipt(messageId: String, to: PeerId) {
        router.sendReadReceipt(ReadReceipt(messageId), to.raw)
    }

    override suspend fun sendFavoriteNotification(to: PeerId, isFavorite: Boolean) {
        router.sendFavoriteNotification(to.raw, isFavorite)
    }

    override suspend fun announceSelf() {
        mesh.sendBroadcastAnnounce()
    }
}
