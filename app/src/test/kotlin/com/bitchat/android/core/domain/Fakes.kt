package com.bitchat.android.core.domain

import com.bitchat.android.core.domain.model.BitMessage
import com.bitchat.android.core.domain.model.Contact
import com.bitchat.android.core.domain.model.ConversationId
import com.bitchat.android.core.domain.model.DeliveryStatus
import com.bitchat.android.core.domain.model.Fingerprint
import com.bitchat.android.core.domain.model.GeohashChannel
import com.bitchat.android.core.domain.model.Peer
import com.bitchat.android.core.domain.model.PeerId
import com.bitchat.android.core.domain.model.PeerIdentity
import com.bitchat.android.core.domain.repository.ContactRepository
import com.bitchat.android.core.domain.repository.MessageRepository
import com.bitchat.android.core.domain.repository.MessageTransport
import com.bitchat.android.core.domain.repository.PeerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Записывает исходящие вызовы транспорта. */
class FakeMessageTransport : MessageTransport {
    data class Public(val content: String, val mentions: List<String>, val channel: String?)
    data class Private(val content: String, val to: PeerId, val recipientNickname: String?, val messageId: String)
    data class Geo(val content: String, val channel: GeohashChannel, val nickname: String?)
    data class Receipt(val messageId: String, val to: PeerId)
    data class Fav(val to: PeerId, val isFavorite: Boolean)

    val publics = mutableListOf<Public>()
    val privates = mutableListOf<Private>()
    val geos = mutableListOf<Geo>()
    val receipts = mutableListOf<Receipt>()
    val favorites = mutableListOf<Fav>()
    var announces = 0

    override suspend fun sendPublic(content: String, mentions: List<String>, channel: String?) {
        publics += Public(content, mentions, channel)
    }

    override suspend fun sendPrivate(content: String, to: PeerId, recipientNickname: String?, messageId: String) {
        privates += Private(content, to, recipientNickname, messageId)
    }

    override suspend fun sendGeohash(content: String, channel: GeohashChannel, nickname: String?) {
        geos += Geo(content, channel, nickname)
    }

    override suspend fun sendReadReceipt(messageId: String, to: PeerId) {
        receipts += Receipt(messageId, to)
    }

    override suspend fun sendFavoriteNotification(to: PeerId, isFavorite: Boolean) {
        favorites += Fav(to, isFavorite)
    }

    override suspend fun announceSelf() {
        announces++
    }
}

/** In-memory лента сообщений. */
class FakeMessageRepository : MessageRepository {
    val store = linkedMapOf<ConversationId, MutableList<BitMessage>>()
    val appended = mutableListOf<Pair<ConversationId, BitMessage>>()

    override fun observeMessages(id: ConversationId): Flow<List<BitMessage>> = flowOf(store[id].orEmpty())
    override suspend fun snapshot(id: ConversationId): List<BitMessage> = store[id].orEmpty()
    override suspend fun append(id: ConversationId, message: BitMessage) {
        store.getOrPut(id) { mutableListOf() }.add(message)
        appended += id to message
    }

    override suspend fun updateDeliveryStatus(messageId: String, status: DeliveryStatus) {}
    override suspend fun remove(messageId: String) {}
    override suspend fun clear(id: ConversationId) { store.remove(id) }
}

/** Фиксированный список пиров. */
class FakePeerRepository(private val peers: List<Peer> = emptyList()) : PeerRepository {
    override fun observePeers(): Flow<List<Peer>> = flowOf(peers)
    override fun observeConnectionState(): Flow<Boolean> = flowOf(peers.any { it.isConnected })
    override suspend fun snapshot(): List<Peer> = peers
    override suspend fun peer(id: PeerId): Peer? = peers.firstOrNull { it.id == id }
}

/** Память favorites/blocked + карта алиас→noiseHex. */
class FakeContactRepository(
    private val aliasToNoiseHex: Map<String, String> = emptyMap(),
    initialFavorites: Set<PeerId> = emptySet(),
) : ContactRepository {
    val favorites = initialFavorites.toMutableSet()
    val blocked = mutableSetOf<PeerId>()

    override fun observeFavorites(): Flow<Set<Fingerprint>> = flowOf(emptySet())
    override suspend fun toggleFavorite(peer: PeerId) { if (!favorites.add(peer)) favorites.remove(peer) }
    override suspend fun isFavorite(peer: PeerId): Boolean = peer in favorites
    override suspend fun setBlocked(peer: PeerId, blocked: Boolean) {
        if (blocked) this.blocked.add(peer) else this.blocked.remove(peer)
    }
    override suspend fun isBlocked(peer: PeerId): Boolean = peer in blocked
    override suspend fun contact(identity: PeerIdentity): Contact? = null
    override suspend fun noiseKeyHexForNostrAlias(alias: PeerId): String? = aliasToNoiseHex[alias.raw]
}
