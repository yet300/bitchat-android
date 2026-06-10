@file:OptIn(ExperimentalTime::class)

package com.app.data.repository

import com.app.data.AppStateStore
import com.app.data.mapper.toDomain
import com.app.domain.model.Conversation
import com.app.domain.model.ConversationId
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
 * Geo conversations are NOT aggregated yet: GeohashRepository still lives in :app and moves into
 * :core:data in a later Phase B step — [conversationKey] returns null for [ConversationId.Geohash]
 * as the documented seam.
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
                                ConversationId.Channel(tag), tag, msgs,
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
        // Geohash timelines are still owned by :app (GeohashRepository); see class doc.
        is ConversationId.Geohash -> null
    }

    private fun messagesOf(id: ConversationId): List<BitchatMessage> = when (id) {
        is ConversationId.PublicMesh -> appStateStore.publicMessages.value
        is ConversationId.Private -> appStateStore.privateMessages.value[id.peer.raw].orEmpty()
        is ConversationId.Channel -> appStateStore.channelMessages.value[id.tag].orEmpty()
        is ConversationId.Geohash -> emptyList()
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
    }
}
