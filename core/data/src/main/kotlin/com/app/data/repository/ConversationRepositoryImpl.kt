@file:OptIn(ExperimentalTime::class)

package com.app.data.repository

import com.app.data.AppStateStore
import com.app.data.mapper.toDomain
import com.app.domain.model.Conversation
import com.app.domain.model.ConversationId
import com.app.domain.model.GeohashChannel
import com.app.domain.model.GeohashLevel
import com.app.domain.model.PeerId
import com.app.domain.repository.ConversationRepository
import com.app.transport.SeenMessageStore
import com.app.transport.mesh.BluetoothMeshService
import com.app.transport.model.BitchatMessage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlin.time.ExperimentalTime

/**
 * Chat-list aggregate over the [AppStateStore] timelines (public + private[peerID] + channel[tag])
 * with live per-conversation unread counters.
 *
 * Geo conversations ride the channel timelines under the legacy `geo:<geohash>` tags
 * (GeohashMessageHandler posts them via addChannelMessage) and surface as
 * [ConversationId.Geohash]; presence/participant tracking stays UI state in :app.
 */
@SingleIn(AppScope::class)
@Inject
internal class ConversationRepositoryImpl(
    private val mesh: BluetoothMeshService,
    private val appStateStore: AppStateStore,
    private val seenMessageStore: SeenMessageStore,
) : ConversationRepository {

    // Live read: myPeerID is re-derived by BMS after a panic reset, never cache it.
    private val myPeerId: String get() = mesh.myPeerID

    override fun observeConversations(): Flow<List<Conversation>> =
        combine(
            appStateStore.publicMessages,
            appStateStore.privateMessages,
            appStateStore.channelMessages,
            appStateStore.unreadCounts,
        ) { pub, priv, chan, unread ->
            buildList {
                if (pub.isNotEmpty()) {
                    add(
                        conversation(
                            ConversationId.PublicMesh, PUBLIC_TITLE, pub,
                            unread[AppStateStore.publicConversationKey()] ?: 0,
                        ),
                    )
                }
                priv.forEach { (peerID, msgs) ->
                    if (msgs.isNotEmpty()) {
                        add(
                            conversation(
                                ConversationId.Private(PeerId(peerID)), titleForPeer(peerID), msgs,
                                unread[AppStateStore.privateConversationKey(peerID)] ?: 0,
                            ),
                        )
                    }
                }
                chan.forEach { (tag, msgs) ->
                    if (msgs.isNotEmpty()) {
                        add(
                            conversation(
                                conversationIdForChannelTag(tag), titleForChannelTag(tag), msgs,
                                unread[AppStateStore.channelConversationKey(tag)] ?: 0,
                            ),
                        )
                    }
                }
            }.sortedByDescending { it.lastActivity }
        }

    override fun observeUnreadCount(): Flow<Int> =
        appStateStore.unreadCounts.map { counts -> counts.values.sum() }

    override suspend fun markRead(id: ConversationId) {
        val key = conversationKey(id) ?: return
        // Persist read ids first so the counter does not bounce back after restart
        seenMessageStore.markReadAll(messagesOf(id).map { it.id })
        appStateStore.markRead(key)
    }

    private fun conversationKey(id: ConversationId): String? = when (id) {
        is ConversationId.PublicMesh -> AppStateStore.publicConversationKey()
        is ConversationId.Private -> AppStateStore.privateConversationKey(id.peer.raw)
        is ConversationId.Channel -> AppStateStore.channelConversationKey(id.tag)
        is ConversationId.Geohash -> AppStateStore.channelConversationKey(GEO_TAG_PREFIX + id.channel.geohash)
    }

    private fun messagesOf(id: ConversationId): List<BitchatMessage> = when (id) {
        is ConversationId.PublicMesh -> appStateStore.publicMessages.value
        is ConversationId.Private -> appStateStore.privateMessages.value[id.peer.raw].orEmpty()
        is ConversationId.Channel -> appStateStore.channelMessages.value[id.tag].orEmpty()
        is ConversationId.Geohash ->
            appStateStore.channelMessages.value[GEO_TAG_PREFIX + id.channel.geohash].orEmpty()
    }

    /** Legacy `geo:<geohash>` channel tags are geo conversations; everything else is a channel. */
    private fun conversationIdForChannelTag(tag: String): ConversationId =
        if (tag.startsWith(GEO_TAG_PREFIX)) {
            val geohash = tag.removePrefix(GEO_TAG_PREFIX)
            ConversationId.Geohash(GeohashChannel(geohashLevelFor(geohash), geohash))
        } else {
            ConversationId.Channel(tag)
        }

    private fun titleForChannelTag(tag: String): String =
        if (tag.startsWith(GEO_TAG_PREFIX)) "#" + tag.removePrefix(GEO_TAG_PREFIX) else tag

    // Same length->level mapping the geohash UI uses
    private fun geohashLevelFor(geohash: String): GeohashLevel = when (geohash.length) {
        in 0..2 -> GeohashLevel.REGION
        in 3..4 -> GeohashLevel.PROVINCE
        5 -> GeohashLevel.CITY
        6 -> GeohashLevel.NEIGHBORHOOD
        7 -> GeohashLevel.BLOCK
        else -> GeohashLevel.BUILDING
    }

    private fun conversation(
        id: ConversationId,
        title: String,
        wire: List<BitchatMessage>,
        unreadCount: Int,
    ): Conversation {
        val last = wire.lastOrNull()
        return Conversation(
            id = id,
            title = title,
            lastMessage = last?.toDomain(id, myPeerId),
            unreadCount = unreadCount,
            lastActivity = last?.timestamp,
        )
    }

    private fun titleForPeer(peerID: String): String =
        mesh.getPeerInfo(peerID)?.nickname?.takeIf { it.isNotBlank() } ?: peerID

    private companion object {
        const val PUBLIC_TITLE = "Public"
        const val GEO_TAG_PREFIX = "geo:"
    }
}
