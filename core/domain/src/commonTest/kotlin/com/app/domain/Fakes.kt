package com.app.domain

import com.app.domain.model.Attachment
import com.app.domain.model.BitMessage
import com.app.domain.model.Channel
import com.app.domain.model.Contact
import com.app.domain.model.ConversationId
import com.app.domain.model.DeliveryStatus
import com.app.domain.model.Fingerprint
import com.app.domain.model.GeoPerson
import com.app.domain.model.GeohashChannel
import com.app.domain.model.MyIdentity
import com.app.domain.model.Peer
import com.app.domain.model.PeerId
import com.app.domain.model.PeerIdentity
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.GeohashRepository
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.MessageRepository
import com.app.domain.repository.MessageTransport
import com.app.domain.repository.PeerRepository
import com.app.domain.repository.SearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Records outgoing transport calls. */
class FakeMessageTransport : MessageTransport {
    data class Public(val content: String, val mentions: List<String>, val channel: String?)
    data class Private(val content: String, val to: PeerId, val recipientNickname: String?, val messageId: String)
    data class Geo(val content: String, val channel: GeohashChannel, val nickname: String?)
    data class Receipt(val messageId: String, val to: PeerId)
    data class Fav(val to: PeerId, val isFavorite: Boolean)
    data class Media(val attachment: Attachment, val target: ConversationId, val messageId: String)

    val publics = mutableListOf<Public>()
    val privates = mutableListOf<Private>()
    val geos = mutableListOf<Geo>()
    val receipts = mutableListOf<Receipt>()
    val favorites = mutableListOf<Fav>()
    val attachments = mutableListOf<Media>()
    val cancelled = mutableListOf<String>()
    var announces = 0
    var cancelResult = true

    override suspend fun sendPublic(content: String, mentions: List<String>, channel: String?) {
        publics += Public(content, mentions, channel)
    }

    override suspend fun sendPrivate(content: String, to: PeerId, recipientNickname: String?, messageId: String) {
        privates += Private(content, to, recipientNickname, messageId)
    }

    override suspend fun sendGeohash(content: String, channel: GeohashChannel, nickname: String?) {
        geos += Geo(content, channel, nickname)
    }

    override suspend fun sendAttachment(attachment: Attachment, target: ConversationId, messageId: String) {
        attachments += Media(attachment, target, messageId)
    }

    override suspend fun cancelTransfer(messageId: String): Boolean {
        cancelled += messageId
        return cancelResult
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

/** In-memory message timeline. */
class FakeMessageRepository : MessageRepository {
    val store = linkedMapOf<ConversationId, MutableList<BitMessage>>()
    val appended = mutableListOf<Pair<ConversationId, BitMessage>>()
    val removed = mutableListOf<String>()
    var clearedAll = false

    override fun observeMessages(id: ConversationId): Flow<List<BitMessage>> = flowOf(store[id].orEmpty())
    override suspend fun snapshot(id: ConversationId): List<BitMessage> = store[id].orEmpty()
    override suspend fun append(id: ConversationId, message: BitMessage) {
        store.getOrPut(id) { mutableListOf() }.add(message)
        appended += id to message
    }

    override suspend fun updateDeliveryStatus(messageId: String, status: DeliveryStatus) {}
    override suspend fun remove(messageId: String) {
        removed += messageId
        store.values.forEach { list -> list.removeAll { it.id == messageId } }
    }

    override suspend fun clear(id: ConversationId) { store.remove(id) }
    override suspend fun clearAll() { store.clear(); clearedAll = true }
}

/** Fixed list of peers. */
class FakePeerRepository(private val peers: List<Peer> = emptyList()) : PeerRepository {
    override fun observePeers(): Flow<List<Peer>> = flowOf(peers)
    override fun observeConnectionState(): Flow<Boolean> = flowOf(peers.any { it.isConnected })
    override suspend fun snapshot(): List<Peer> = peers
    override suspend fun peer(id: PeerId): Peer? = peers.firstOrNull { it.id == id }
}

/** In-memory favorites/blocked + alias->noiseHex map + contacts by noise-key hex. */
class FakeContactRepository(
    private val aliasToNoiseHex: Map<String, String> = emptyMap(),
    initialFavorites: Set<PeerId> = emptySet(),
    private val contactsByNoiseHex: Map<String, Contact> = emptyMap(),
) : ContactRepository {
    val favorites = initialFavorites.toMutableSet()
    val blocked = mutableSetOf<PeerId>()
    var clearedAll = false

    override fun observeFavorites(): Flow<Set<Fingerprint>> = flowOf(emptySet())
    override fun observeContacts(): Flow<List<Contact>> = flowOf(contactsByNoiseHex.values.toList())
    override fun observeVerified(noiseKeyHex: String): Flow<Boolean> = flowOf(false)
    override suspend fun toggleFavorite(peer: PeerId) { if (!favorites.add(peer)) favorites.remove(peer) }
    override suspend fun isFavorite(peer: PeerId): Boolean = peer in favorites
    override suspend fun setBlocked(peer: PeerId, blocked: Boolean) {
        if (blocked) this.blocked.add(peer) else this.blocked.remove(peer)
    }
    override suspend fun isBlocked(peer: PeerId): Boolean = peer in blocked
    override suspend fun contact(identity: PeerIdentity): Contact? =
        contactsByNoiseHex[identity.noiseKeyHex]
    override suspend fun noiseKeyHexForNostrAlias(alias: PeerId): String? = aliasToNoiseHex[alias.raw]
    override suspend fun clearAll() { favorites.clear(); blocked.clear(); clearedAll = true }
}

/** In-memory identity/verification. */
class FakeIdentityRepository(
    initialVerified: Set<Fingerprint> = emptySet(),
) : IdentityRepository {
    val verified = initialVerified.toMutableSet()
    var panicWiped = false

    override suspend fun myIdentity(): MyIdentity =
        MyIdentity(PeerId("1111111111111111"), Fingerprint("a".repeat(64)), nickname = "me")

    override fun observeVerifiedFingerprints(): Flow<Set<Fingerprint>> = flowOf(verified)
    override suspend fun isVerified(fingerprint: Fingerprint): Boolean = fingerprint in verified
    override suspend fun setVerified(fingerprint: Fingerprint, verified: Boolean) {
        if (verified) this.verified.add(fingerprint) else this.verified.remove(fingerprint)
    }
    override suspend fun panicWipe() { panicWiped = true }
}

/** Geohash participants resolution + blocking. */
class FakeGeohashRepository(
    private val nicknameToPubkey: Map<String, String> = emptyMap(),
    private val shortIdToPubkey: Map<String, String> = emptyMap(),
) : GeohashRepository {
    val blocked = mutableSetOf<String>()
    val dmStarted = mutableListOf<String>()

    override fun observeParticipants(): Flow<List<GeoPerson>> = flowOf(emptyList())
    override fun observeSelectedChannel(): Flow<ConversationId> = flowOf(ConversationId.PublicMesh)
    override fun observeParticipantCounts(): Flow<Map<String, Int>> = flowOf(emptyMap())
    override suspend fun select(channel: ConversationId) {}
    override suspend fun startDirectMessage(pubkeyHex: String): ConversationId {
        dmStarted += pubkeyHex
        return ConversationId.Private(PeerId("nostr_${pubkeyHex.take(16)}"))
    }
    override suspend fun setBlocked(pubkeyHex: String, blocked: Boolean) {
        if (blocked) this.blocked.add(pubkeyHex) else this.blocked.remove(pubkeyHex)
    }
    override suspend fun isTeleported(pubkeyHex: String): Boolean = false
    override suspend fun pubkeyForNickname(nickname: String): String? = nicknameToPubkey[nickname]
    override suspend fun pubkeyForShortId(shortId: String): String? = shortIdToPubkey[shortId]
}

/** Configurable search results. */
class FakeSearchRepository(
    private val messages: List<BitMessage> = emptyList(),
    private val contacts: List<Contact> = emptyList(),
    private val channels: List<Channel> = emptyList(),
) : SearchRepository {
    val queries = mutableListOf<String>()
    override suspend fun searchMessages(query: String): List<BitMessage> { queries += query; return messages }
    override suspend fun searchContacts(query: String): List<Contact> = contacts
    override suspend fun searchChannels(query: String): List<Channel> = channels
}
