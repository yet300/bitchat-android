package com.bitchat.android.core.domain.repository

import com.bitchat.android.core.domain.model.GeohashChannel
import com.bitchat.android.core.domain.model.PeerId

/**
 * Outgoing-transport port. Hides the mesh<->Nostr choice (the current MessageRouter), the outbox
 * and addressing — that's an infrastructure detail. The domain just "sends".
 */
interface MessageTransport {

    /** Public/channel message (channel == null -> shared mesh chat). */
    suspend fun sendPublic(content: String, mentions: List<String>, channel: String?)

    /** Private message. The route (mesh/Nostr) is chosen by the implementation. */
    suspend fun sendPrivate(content: String, to: PeerId, recipientNickname: String?, messageId: String)

    /** Message to a geo-chat (Nostr ephemeral event). */
    suspend fun sendGeohash(content: String, channel: GeohashChannel, nickname: String?)

    /** Read receipt. */
    suspend fun sendReadReceipt(messageId: String, to: PeerId)

    /** (Un)favorite notification — sent over mesh or Nostr. */
    suspend fun sendFavoriteNotification(to: PeerId, isFavorite: Boolean)

    /** Announce self to the network (e.g. after a nickname change). */
    suspend fun announceSelf()
}
