package com.app.data.repository

import com.app.data.AppStateStore
import com.app.data.mapper.toDomain
import com.app.data.mapper.toWire
import com.app.data.mapper.toWireStatus
import com.app.domain.model.BitMessage
import com.app.domain.model.ConversationId
import com.app.domain.model.DeliveryStatus
import com.app.domain.repository.MessageRepository
import com.app.transport.model.BitchatMessage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Domain message timeline over the wire-level [AppStateStore] (the process-wide store written by the
 * mesh/Nostr sink). Reads map wire -> domain per conversation bucket; writes map domain -> wire.
 */
@SingleIn(AppScope::class)
@Inject
internal class MessageRepositoryImpl(
    private val appStateStore: AppStateStore,
) : MessageRepository {

    // Ownership (isMine) needs my current mesh peer id from the identity layer (a later Phase B step);
    // until then messages map with a null peer id, i.e. isMine == false.
    private val myPeerId: String? = null

    override fun observeMessages(id: ConversationId): Flow<List<BitMessage>> = when (id) {
        ConversationId.PublicMesh ->
            appStateStore.publicMessages.map { it.toDomainList(id) }
        is ConversationId.Private ->
            appStateStore.privateMessages.map { (it[id.peer.raw] ?: emptyList()).toDomainList(id) }
        is ConversationId.Channel ->
            appStateStore.channelMessages.map { (it[id.tag] ?: emptyList()).toDomainList(id) }
        is ConversationId.Geohash ->
            appStateStore.channelMessages.map { (it[id.geoTag()] ?: emptyList()).toDomainList(id) }
    }

    override suspend fun snapshot(id: ConversationId): List<BitMessage> = when (id) {
        ConversationId.PublicMesh -> appStateStore.publicMessages.value.toDomainList(id)
        is ConversationId.Private -> (appStateStore.privateMessages.value[id.peer.raw] ?: emptyList()).toDomainList(id)
        is ConversationId.Channel -> (appStateStore.channelMessages.value[id.tag] ?: emptyList()).toDomainList(id)
        is ConversationId.Geohash -> (appStateStore.channelMessages.value[id.geoTag()] ?: emptyList()).toDomainList(id)
    }

    override suspend fun append(id: ConversationId, message: BitMessage) {
        val wire = message.toWire()
        when (id) {
            ConversationId.PublicMesh -> appStateStore.addPublicMessage(wire)
            is ConversationId.Private -> appStateStore.addPrivateMessage(id.peer.raw, wire)
            is ConversationId.Channel -> appStateStore.addChannelMessage(id.tag, wire)
            is ConversationId.Geohash -> appStateStore.addChannelMessage(id.geoTag(), wire)
        }
    }

    override suspend fun updateDeliveryStatus(messageId: String, status: DeliveryStatus) {
        appStateStore.updatePrivateMessageStatus(messageId, status.toWireStatus())
    }

    override suspend fun remove(messageId: String) {
        appStateStore.removeMessage(messageId)
    }

    override suspend fun clear(id: ConversationId) {
        when (id) {
            ConversationId.PublicMesh -> appStateStore.clearPublic()
            is ConversationId.Private -> appStateStore.clearPrivate(id.peer.raw)
            is ConversationId.Channel -> appStateStore.clearChannel(id.tag)
            is ConversationId.Geohash -> appStateStore.clearChannel(id.geoTag())
        }
    }

    override suspend fun clearAll() {
        appStateStore.clear()
    }

    private fun List<BitchatMessage>.toDomainList(id: ConversationId): List<BitMessage> =
        map { it.toDomain(id, myPeerId) }

    private fun ConversationId.Geohash.geoTag(): String = AppStateStore.geoChannelName(channel.geohash)
}
