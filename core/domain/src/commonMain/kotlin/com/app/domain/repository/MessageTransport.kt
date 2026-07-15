package com.app.domain.repository

import com.app.domain.model.Attachment
import com.app.domain.model.ConversationId
import com.app.domain.model.GeohashChannel
import com.app.domain.model.PeerId

/**
 * Outgoing-transport port. Hides the mesh<->Nostr choice (the current MessageRouter), the outbox
 * and addressing — that's an infrastructure detail. The domain just "sends".
 */
interface MessageTransport {

    /** Public/channel message (channel == null -> shared mesh chat). */
    suspend fun sendPublic(content: String, mentions: List<String>, channel: String?)

    /** Public/channel message whose relayed self-copy must retain [messageId]. */
    suspend fun sendPublic(content: String, mentions: List<String>, channel: String?, messageId: String) =
        sendPublic(content, mentions, channel)

    /** Private message. The route (mesh/Nostr) is chosen by the implementation. */
    suspend fun sendPrivate(content: String, to: PeerId, recipientNickname: String?, messageId: String)

    /** Message to a geo-chat (Nostr ephemeral event). */
    suspend fun sendGeohash(content: String, channel: GeohashChannel, nickname: String?)

    /** Send a media attachment to the target conversation. */
    suspend fun sendAttachment(attachment: Attachment, target: ConversationId, messageId: String)

    /** Cancel an in-flight attachment transfer. Returns true if it was cancelled. */
    suspend fun cancelTransfer(messageId: String): Boolean

    /** Read receipt. */
    suspend fun sendReadReceipt(messageId: String, to: PeerId)

    /** (Un)favorite notification — sent over mesh or Nostr. */
    suspend fun sendFavoriteNotification(to: PeerId, isFavorite: Boolean)

    /** Announce self to the network (e.g. after a nickname change). */
    suspend fun announceSelf()
}
