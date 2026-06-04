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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Domain message timeline over the wire-level [AppStateStore] (the process-wide store written by the
 * mesh/Nostr sink). Reads map wire -> domain per conversation bucket; writes map domain -> wire.
 */
@SingleIn(AppScope::class)
@Inject
internal class MessageRepositoryImpl : MessageRepository {

    // Ownership (isMine) needs my current mesh peer id from the identity layer (a later Phase B step);
    // until then messages map with a null peer id, i.e. isMine == false.
    private val myPeerId: String? = null

    override fun observeMessages(id: ConversationId): Flow<List<BitMessage>> = when (id) {
        ConversationId.PublicMesh ->
            AppStateStore.publicMessages.map { it.toDomainList(id) }
        is ConversationId.Private ->
            AppStateStore.privateMessages.map { (it[id.peer.raw] ?: emptyList()).toDomainList(id) }
        is ConversationId.Channel ->
            AppStateStore.channelMessages.map { (it[id.tag] ?: emptyList()).toDomainList(id) }
        is ConversationId.Geohash ->
            flowOf(emptyList()) // geo timelines live in GeohashRepository (a later Phase B step)
    }

    override suspend fun snapshot(id: ConversationId): List<BitMessage> = when (id) {
        ConversationId.PublicMesh -> AppStateStore.publicMessages.value.toDomainList(id)
        is ConversationId.Private -> (AppStateStore.privateMessages.value[id.peer.raw] ?: emptyList()).toDomainList(id)
        is ConversationId.Channel -> (AppStateStore.channelMessages.value[id.tag] ?: emptyList()).toDomainList(id)
        is ConversationId.Geohash -> emptyList()
    }

    override suspend fun append(id: ConversationId, message: BitMessage) {
        val wire = message.toWire()
        when (id) {
            ConversationId.PublicMesh -> AppStateStore.addPublicMessage(wire)
            is ConversationId.Private -> AppStateStore.addPrivateMessage(id.peer.raw, wire)
            is ConversationId.Channel -> AppStateStore.addChannelMessage(id.tag, wire)
            is ConversationId.Geohash -> Unit // geo timelines handled elsewhere for now
        }
    }

    override suspend fun updateDeliveryStatus(messageId: String, status: DeliveryStatus) {
        AppStateStore.updatePrivateMessageStatus(messageId, status.toWireStatus())
    }

    override suspend fun remove(messageId: String) {
        AppStateStore.removeMessage(messageId)
    }

    override suspend fun clear(id: ConversationId) {
        when (id) {
            ConversationId.PublicMesh -> AppStateStore.clearPublic()
            is ConversationId.Private -> AppStateStore.clearPrivate(id.peer.raw)
            is ConversationId.Channel -> AppStateStore.clearChannel(id.tag)
            is ConversationId.Geohash -> Unit
        }
    }

    override suspend fun clearAll() {
        AppStateStore.clear()
    }

    private fun List<BitchatMessage>.toDomainList(id: ConversationId): List<BitMessage> =
        map { it.toDomain(id, myPeerId) }
}
