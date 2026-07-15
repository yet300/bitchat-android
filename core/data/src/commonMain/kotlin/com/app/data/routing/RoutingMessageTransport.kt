package com.app.data.routing

import com.app.data.media.AttachmentSender
import com.app.data.nostr.GeohashMessageSender
import com.app.domain.model.Attachment
import com.app.domain.model.ConversationId
import com.app.domain.model.GeohashChannel
import com.app.domain.model.PeerId
import com.app.domain.repository.MessageTransport
import com.app.transport.mesh.MeshService
import com.app.transport.model.ReadReceipt
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Adapter implementing the domain [MessageTransport] port over the existing concrete routing
 * ([MessageRouter] + [MeshService]). It hides the mesh<->Nostr choice and the addressing
 * (domain [PeerId] -> String peerID) from the domain. Replacing the if/else routing with a tested
 * policy (the two-tier SPI) is a later phase; this is the "as is" wrapper.
 */
@SingleIn(AppScope::class)
@Inject
internal class RoutingMessageTransport(
    private val mesh: MeshService,
    private val router: MessageRouter,
    private val geohashSender: GeohashMessageSender,
    private val attachmentSender: AttachmentSender,
) : MessageTransport {

    override suspend fun sendPublic(content: String, mentions: List<String>, channel: String?) {
        mesh.sendMessage(content, mentions, channel)
    }

    override suspend fun sendPublic(content: String, mentions: List<String>, channel: String?, messageId: String) {
        mesh.sendMessage(content, mentions, channel, messageId)
    }

    override suspend fun sendPrivate(content: String, to: PeerId, recipientNickname: String?, messageId: String) {
        router.sendPrivate(content, to.raw, recipientNickname ?: "", messageId)
    }

    override suspend fun sendGeohash(content: String, channel: GeohashChannel, nickname: String?) {
        // Post a kind-20000 ephemeral event signed by the per-geohash identity (the local echo is
        // added by SendMessageUseCase; incoming + presence are ingested by GeohashRepository).
        geohashSender.sendPublic(content, channel.geohash, nickname)
    }

    override suspend fun sendAttachment(attachment: Attachment, target: ConversationId, messageId: String) {
        attachmentSender.send(attachment, target, messageId)
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
