@file:OptIn(ExperimentalTime::class)

package com.app.data

import kotlin.time.ExperimentalTime

import com.app.crypto.EncryptionService
import com.app.transport.IncomingMessageSink
import com.app.transport.SeenMessageStore
import com.app.transport.model.BitchatMessage
import com.app.transport.model.DeliveryStatus
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
) : IncomingMessageSink {

    companion object {
        private const val SEEN_IDS_CAP = 2048

        fun publicConversationKey() = "public"
        fun privateConversationKey(peerID: String) = "private:" + peerID
        fun channelConversationKey(channel: String) = "channel:" + channel
    }

    // Global de-dup set by message id to avoid duplicate keys in Compose lists.
    // LRU-capped at SEEN_IDS_CAP: oldest ids are evicted, matching the bounded timelines.
    private val seenMessageIds = LinkedHashSet<String>()

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

    /** Reset the unread counter of a conversation (the repository persists read ids). */
    fun markRead(conversationKey: String) {
        synchronized(this) {
            if (_unreadCounts.value.containsKey(conversationKey)) {
                _unreadCounts.value = _unreadCounts.value - conversationKey
            }
        }
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

    override fun addPublicMessage(msg: BitchatMessage) {
        synchronized(this) {
            if (!rememberSeen(msg.id)) return
            _publicMessages.value = _publicMessages.value + msg
            countUnread(publicConversationKey(), msg)
        }
    }

    override fun addPrivateMessage(peerID: String, msg: BitchatMessage) {
        synchronized(this) {
            if (!rememberSeen(msg.id)) return
            val map = _privateMessages.value.toMutableMap()
            val list = (map[peerID] ?: emptyList()) + msg
            map[peerID] = list
            _privateMessages.value = map
            countUnread(privateConversationKey(peerID), msg)
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

    fun updatePrivateMessageStatus(messageID: String, status: DeliveryStatus) {
        synchronized(this) {
            val map = _privateMessages.value.toMutableMap()
            var changed = false
            map.keys.toList().forEach { peer ->
                val list = map[peer]?.toMutableList() ?: mutableListOf()
                val idx = list.indexOfFirst { it.id == messageID }
                if (idx >= 0) {
                    val current = list[idx].deliveryStatus
                    // Do not downgrade (e.g., Read -> Delivered)
                    if (statusPriority(status) >= statusPriority(current)) {
                        list[idx] = list[idx].copy(deliveryStatus = status)
                        map[peer] = list
                        changed = true
                    }
                }
            }
            if (changed) {
                _privateMessages.value = map
            }
        }
    }

    override fun addChannelMessage(channel: String, msg: BitchatMessage) {
        synchronized(this) {
            if (!rememberSeen(msg.id)) return
            val map = _channelMessages.value.toMutableMap()
            val list = (map[channel] ?: emptyList()) + msg
            map[channel] = list
            _channelMessages.value = map
            countUnread(channelConversationKey(channel), msg)
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
    }

    /** Clear the public mesh timeline. */
    fun clearPublic() {
        synchronized(this) { _publicMessages.value = emptyList() }
    }

    /** Clear a single private conversation's timeline. */
    fun clearPrivate(peerID: String) {
        synchronized(this) {
            _privateMessages.value = _privateMessages.value.toMutableMap().apply { remove(peerID) }
        }
    }

    /** Clear a single channel's timeline. */
    fun clearChannel(channel: String) {
        synchronized(this) {
            _channelMessages.value = _channelMessages.value.toMutableMap().apply { remove(channel) }
        }
    }

    // Clear all in-memory state (used for full app shutdown)
    fun clear() {
        synchronized(this) {
            seenMessageIds.clear()
            _peers.value = emptyList()
            _publicMessages.value = emptyList()
            _privateMessages.value = emptyMap()
            _channelMessages.value = emptyMap()
            _unreadCounts.value = emptyMap()
        }
    }
}
