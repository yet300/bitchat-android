@file:OptIn(ExperimentalTime::class)

package com.app.data

import kotlin.time.ExperimentalTime

import com.app.crypto.EncryptionService
import com.app.domain.model.PeerId
import com.app.domain.repository.ContactRepository
import com.app.transport.IncomingMessageSink
import com.app.transport.SeenMessageStore
import com.app.transport.model.BitchatMessage
import com.app.transport.model.DeliveryStatus
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Process-wide in-memory state store that survives Activity recreation.
 * The foreground Mesh service updates this store; UI subscribes/hydrates from it.
 *
 * App-scoped graph singleton: the data-layer repositories inject it directly; the not-yet-graph
 * :app consumers (mesh sink wiring, god-classes) resolve the single instance via the graph.
 */
@SingleIn(AppScope::class)
@Inject
class AppStateStore(
    // Identity source for the own-message check (read live — survives panic reset)
    private val encryptionService: EncryptionService,
    // Persisted read-state so unread counters survive process restart
    private val seenMessageStore: SeenMessageStore,
    // Drops incoming messages from blocked peers (parity with legacy MeshDelegateHandler). Lazy: the
    // ContactRepository's transitive deps (mesh) write back into this sink, so a deferred provider
    // breaks that construction cycle.
    private val contacts: () -> ContactRepository,
    // Persists timelines into the encrypted DB and rehydrates them on startup. Defaults to a no-op so
    // unit tests of the in-memory behaviour need not wire a database.
    private val persistence: MessagePersistence = MessagePersistence.NoOp,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : IncomingMessageSink {

    /**
     * Rehydrate the timelines and de-dup state from the DB. Idempotent; runs once at startup (and is
     * called directly by persistence tests). With the default [MessagePersistence.NoOp] it is a no-op.
     */
    suspend fun hydrate() {
        val loaded = persistence.load(TIMELINE_CAP.toLong())
        if (loaded.isEmpty()) return
        synchronized(this) {
            loaded.forEach { (key, messages) ->
                messages.forEach { msg ->
                    seenMessageIds.add(msg.id)
                    if (key == publicConversationKey()) seenPublicMessageKeys.add(publicMessageKey(msg))
                }
                val capped = messages.takeLast(TIMELINE_CAP)
                when {
                    key == publicConversationKey() -> _publicMessages.value = capped
                    key.startsWith("private:") ->
                        _privateMessages.value = _privateMessages.value + (key.removePrefix("private:") to capped)
                    key.startsWith("channel:") ->
                        _channelMessages.value = _channelMessages.value + (key.removePrefix("channel:") to capped)
                }
            }
        }
    }

    companion object {
        private const val SEEN_IDS_CAP = 2048

        // Per-conversation timeline bound (drop-oldest). Keeps long foreground sessions
        // from growing without limit and keeps retained messages roughly within the
        // seen-id LRU horizon (audit finding A10).
        private const val TIMELINE_CAP = 500

        fun publicConversationKey() = "public"
        fun privateConversationKey(peerID: String) = "private:" + peerID
        fun channelConversationKey(channel: String) = "channel:" + channel

        /** Geo (location) chats ride the channel timelines under a reserved tag prefix. */
        const val GEO_TAG_PREFIX = "geo:"

        /** Channel-bucket name a [ConversationId.Geohash] timeline is stored under. */
        fun geoChannelName(geohash: String) = GEO_TAG_PREFIX + geohash
    }

    // Global de-dup set by message id to avoid duplicate keys in Compose lists.
    // LRU-capped at SEEN_IDS_CAP: oldest ids are evicted, matching the bounded timelines.
    private val seenMessageIds = LinkedHashSet<String>()

    // Content-derived keys of public messages: request-sync replays arrive with different
    // android-side ids but identical content (upstream #707); LRU-capped like seen ids.
    private val seenPublicMessageKeys = LinkedHashSet<String>()

    /** Adds [id] to the de-dup LRU. Returns false when the id was already present. */
    private fun rememberSeen(id: String): Boolean {
        if (!seenMessageIds.add(id)) return false
        if (seenMessageIds.size > SEEN_IDS_CAP) {
            val iterator = seenMessageIds.iterator()
            iterator.next()
            iterator.remove()
        }
        return true
    }

    // Per-conversation unread counters, keyed by *ConversationKey() helpers
    private val _unreadCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadCounts: StateFlow<Map<String, Int>> = _unreadCounts.asStateFlow()

    private val myPeerId: String?
        get() = try { encryptionService.getIdentityFingerprint().take(16) } catch (_: Exception) { null }

    /** Increment the unread counter unless the message is our own or already persisted as read. */
    private fun countUnread(conversationKey: String, msg: BitchatMessage) {
        val sender = msg.senderPeerID
        if (sender == null || sender == myPeerId) return
        if (try { seenMessageStore.hasRead(msg.id) } catch (_: Exception) { false }) return
        _unreadCounts.value = _unreadCounts.value.toMutableMap().apply {
            put(conversationKey, (get(conversationKey) ?: 0) + 1)
        }
    }

    /**
     * Reset the unread counter of a conversation. [persistReadIds] (the repository's
     * SeenMessageStore write) runs under the same monitor the add*Message paths take, so a
     * message cannot slip between the persist snapshot and the counter reset and end up
     * neither counted nor persisted as read (audit finding A9).
     */
    fun markRead(conversationKey: String, persistReadIds: ((List<String>) -> Unit)? = null) {
        synchronized(this) {
            persistReadIds?.invoke(messagesForKey(conversationKey).map { it.id })
            if (_unreadCounts.value.containsKey(conversationKey)) {
                _unreadCounts.value = _unreadCounts.value - conversationKey
            }
        }
    }

    private fun messagesForKey(conversationKey: String): List<BitchatMessage> = when {
        conversationKey == publicConversationKey() -> _publicMessages.value
        conversationKey.startsWith("private:") ->
            _privateMessages.value[conversationKey.removePrefix("private:")].orEmpty()
        conversationKey.startsWith("channel:") ->
            _channelMessages.value[conversationKey.removePrefix("channel:")].orEmpty()
        else -> emptyList()
    }
    // Connected peer IDs (mesh ephemeral IDs)
    private val _peers = MutableStateFlow<List<String>>(emptyList())
    val peers: StateFlow<List<String>> = _peers.asStateFlow()

    // Public mesh timeline messages (non-channel)
    private val _publicMessages = MutableStateFlow<List<BitchatMessage>>(emptyList())
    val publicMessages: StateFlow<List<BitchatMessage>> = _publicMessages.asStateFlow()

    // Private messages by peerID
    private val _privateMessages = MutableStateFlow<Map<String, List<BitchatMessage>>>(emptyMap())
    val privateMessages: StateFlow<Map<String, List<BitchatMessage>>> = _privateMessages.asStateFlow()

    // Channel messages by channel name
    private val _channelMessages = MutableStateFlow<Map<String, List<BitchatMessage>>>(emptyMap())
    val channelMessages: StateFlow<Map<String, List<BitchatMessage>>> = _channelMessages.asStateFlow()

    override fun setPeers(ids: List<String>) {
        _peers.value = ids
    }

    /**
     * Drop messages from a blocked peer (checked by the sender's peer id so my own local echoes —
     * appended with my peer id — are never filtered). Mirrors the legacy block enforcement that was
     * lost with MeshDelegateHandler.
     */
    private fun isFromBlocked(msg: BitchatMessage): Boolean =
        msg.senderPeerID?.let { contacts().isBlocked(PeerId(it)) } == true

    override fun addPublicMessage(msg: BitchatMessage) {
        if (isFromBlocked(msg)) return
        synchronized(this) {
            val publicKey = publicMessageKey(msg)
            if (seenPublicMessageKeys.contains(publicKey)) return
            if (!rememberSeen(msg.id)) return
            seenPublicMessageKeys.add(publicKey)
            if (seenPublicMessageKeys.size > SEEN_IDS_CAP) {
                val iterator = seenPublicMessageKeys.iterator()
                iterator.next()
                iterator.remove()
            }
            _publicMessages.value = (_publicMessages.value + msg).takeLast(TIMELINE_CAP)
            countUnread(publicConversationKey(), msg)
            persistence.persist(publicConversationKey(), msg)
        }
    }

    private fun publicMessageKey(msg: BitchatMessage): String {
        val sender = msg.senderPeerID ?: msg.sender
        return listOf(
            sender,
            msg.timestamp.toEpochMilliseconds().toString(),
            msg.type.name,
            msg.channel ?: "",
            msg.content,
        ).joinToString("\u001F")
    }

    override fun addPrivateMessage(peerID: String, msg: BitchatMessage) {
        if (isFromBlocked(msg)) return
        synchronized(this) {
            if (!rememberSeen(msg.id)) return
            val map = _privateMessages.value.toMutableMap()
            map[peerID] = ((map[peerID] ?: emptyList()) + msg).takeLast(TIMELINE_CAP)
            _privateMessages.value = map
            countUnread(privateConversationKey(peerID), msg)
            persistence.persist(privateConversationKey(peerID), msg)
        }
    }

    private fun statusPriority(status: DeliveryStatus?): Int = when (status) {
        null -> 0
        is DeliveryStatus.Sending -> 1
        is DeliveryStatus.Sent -> 2
        is DeliveryStatus.PartiallyDelivered -> 3
        is DeliveryStatus.Delivered -> 4
        is DeliveryStatus.Read -> 5
        is DeliveryStatus.Failed -> 0
    }

    /**
     * Apply a delivery/transfer status to whichever timeline holds [messageID] (private, public or
     * channel). Used by DM ACKs and by media transfer-progress updates, which can target any kind.
     * Never downgrades (e.g. Read -> Delivered), so out-of-order events are safe.
     */
    fun updateMessageStatus(messageID: String, status: DeliveryStatus) {
        synchronized(this) {
            _privateMessages.value.toMutableMap().let { map ->
                var changed = false
                map.keys.toList().forEach { peer ->
                    map[peer]?.withStatus(messageID, status)?.let { map[peer] = it; changed = true }
                }
                if (changed) _privateMessages.value = map
            }
            _publicMessages.value.withStatus(messageID, status)?.let { _publicMessages.value = it }
            _channelMessages.value.toMutableMap().let { map ->
                var changed = false
                map.keys.toList().forEach { tag ->
                    map[tag]?.withStatus(messageID, status)?.let { map[tag] = it; changed = true }
                }
                if (changed) _channelMessages.value = map
            }
        }
        persistence.updateStatus(messageID, status)
    }

    /** Returns a copy with [messageID]'s status updated, or null if absent / would downgrade. */
    private fun List<BitchatMessage>.withStatus(messageID: String, status: DeliveryStatus): List<BitchatMessage>? {
        val idx = indexOfFirst { it.id == messageID }
        if (idx < 0) return null
        if (statusPriority(status) < statusPriority(this[idx].deliveryStatus)) return null
        return toMutableList().apply { this[idx] = this[idx].copy(deliveryStatus = status) }
    }

    override fun onDeliveryAck(messageId: String, peerId: String) {
        updateMessageStatus(messageId, DeliveryStatus.Delivered(to = peerId, at = Clock.System.now()))
    }

    override fun onReadReceipt(messageId: String, peerId: String) {
        updateMessageStatus(messageId, DeliveryStatus.Read(by = peerId, at = Clock.System.now()))
    }

    override fun addChannelMessage(channel: String, msg: BitchatMessage) {
        if (isFromBlocked(msg)) return
        synchronized(this) {
            if (!rememberSeen(msg.id)) return
            val map = _channelMessages.value.toMutableMap()
            map[channel] = ((map[channel] ?: emptyList()) + msg).takeLast(TIMELINE_CAP)
            _channelMessages.value = map
            countUnread(channelConversationKey(channel), msg)
            persistence.persist(channelConversationKey(channel), msg)
        }
    }

    /** Remove a message by id from every timeline (public/private/channel). */
    fun removeMessage(messageId: String) {
        synchronized(this) {
            seenMessageIds.remove(messageId)
            _publicMessages.value = _publicMessages.value.filterNot { it.id == messageId }
            _privateMessages.value = _privateMessages.value.mapValues { (_, list) -> list.filterNot { it.id == messageId } }
            _channelMessages.value = _channelMessages.value.mapValues { (_, list) -> list.filterNot { it.id == messageId } }
        }
        persistence.delete(messageId)
    }

    /** Clear the public mesh timeline. */
    fun clearPublic() {
        synchronized(this) { _publicMessages.value = emptyList() }
        persistence.clear(publicConversationKey())
    }

    /** Clear a single private conversation's timeline. */
    fun clearPrivate(peerID: String) {
        synchronized(this) {
            _privateMessages.value = _privateMessages.value.toMutableMap().apply { remove(peerID) }
        }
        persistence.clear(privateConversationKey(peerID))
    }

    /** Clear a single channel's timeline. */
    fun clearChannel(channel: String) {
        synchronized(this) {
            _channelMessages.value = _channelMessages.value.toMutableMap().apply { remove(channel) }
        }
        persistence.clear(channelConversationKey(channel))
    }

    // Clear all in-memory state (used for full app shutdown)
    fun clear() {
        synchronized(this) {
            seenMessageIds.clear()
            seenPublicMessageKeys.clear()
            _peers.value = emptyList()
            _publicMessages.value = emptyList()
            _privateMessages.value = emptyMap()
            _channelMessages.value = emptyMap()
            _unreadCounts.value = emptyMap()
        }
        persistence.clear(null)
    }

    // Declared last so all timeline/de-dup fields above are initialized before hydration runs.
    init {
        scope.launch { hydrate() }
    }
}
