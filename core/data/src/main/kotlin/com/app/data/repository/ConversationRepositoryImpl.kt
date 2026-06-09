@file:OptIn(ExperimentalTime::class)

package com.app.data.repository

import com.app.data.AppStateStore
import com.app.data.mapper.toDomain
import com.app.domain.model.Conversation
import com.app.domain.model.ConversationId
import com.app.domain.model.PeerId
import com.app.domain.repository.ConversationRepository
import com.app.transport.mesh.BluetoothMeshService
import com.app.transport.model.BitchatMessage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlin.time.ExperimentalTime

/**
 * Chat-list aggregate over the [AppStateStore] timelines (public + private[peerID] + channel[tag]).
 * Geo conversations (GeohashRepository) and unread tracking (still in the UI layer) are not
 * aggregated here yet; ownership ([com.app.domain.model.BitMessage.isMine]) awaits the identity layer.
 */
@SingleIn(AppScope::class)
@Inject
internal class ConversationRepositoryImpl(
    private val mesh: BluetoothMeshService,
    private val appStateStore: AppStateStore,
) : ConversationRepository {

    private val myPeerId: String? = null

    override fun observeConversations(): Flow<List<Conversation>> =
        combine(
            appStateStore.publicMessages,
            appStateStore.privateMessages,
            appStateStore.channelMessages,
        ) { pub, priv, chan ->
            buildList {
                if (pub.isNotEmpty()) {
                    add(conversation(ConversationId.PublicMesh, PUBLIC_TITLE, pub))
                }
                priv.forEach { (peerID, msgs) ->
                    if (msgs.isNotEmpty()) {
                        add(conversation(ConversationId.Private(PeerId(peerID)), titleForPeer(peerID), msgs))
                    }
                }
                chan.forEach { (tag, msgs) ->
                    if (msgs.isNotEmpty()) {
                        add(conversation(ConversationId.Channel(tag), tag, msgs))
                    }
                }
            }.sortedByDescending { it.lastActivity }
        }

    // Unread tracking still lives in the UI layer (MessageManager); not aggregated here yet.
    override fun observeUnreadCount(): Flow<Int> = flowOf(0)

    override suspend fun markRead(id: ConversationId) {
        // No-op until unread tracking moves into the data layer.
    }

    private fun conversation(id: ConversationId, title: String, wire: List<BitchatMessage>): Conversation {
        val last = wire.lastOrNull()
        return Conversation(
            id = id,
            title = title,
            lastMessage = last?.toDomain(id, myPeerId),
            unreadCount = 0,
            lastActivity = last?.timestamp,
        )
    }

    private fun titleForPeer(peerID: String): String =
        mesh.getPeerInfo(peerID)?.nickname?.takeIf { it.isNotBlank() } ?: peerID

    private companion object {
        const val PUBLIC_TITLE = "Public"
    }
}
