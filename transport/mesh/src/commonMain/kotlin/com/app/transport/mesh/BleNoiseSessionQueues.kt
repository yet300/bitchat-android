package com.app.transport.mesh

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock

/**
 * Pending Noise traffic held until a session with a peer is established.
 *
 * Port of iOS `Services/BLE/BLENoiseSessionQueues.swift`. Without this, a first DM (or a typed
 * Noise payload such as a receipt/verify/vouch) issued before the XX handshake finishes is dropped
 * on the floor after `initiateHandshake`.
 *
 * Caps are defensive (iOS is unbounded under the collections queue); oldest entries drop when a
 * per-peer or peer-count limit is hit so a handshake flood cannot grow the bag without bound.
 */
class BleNoiseSessionQueues(
    private val maxPrivateMessagesPerPeer: Int = DEFAULT_MAX_PRIVATE_PER_PEER,
    private val maxTypedPayloadsPerPeer: Int = DEFAULT_MAX_TYPED_PER_PEER,
    private val maxPeers: Int = DEFAULT_MAX_PEERS,
) {
    data class PendingPrivateMessage(
        val content: String,
        val messageID: String,
    )

    private val lock = Lock()
    private val privateMessagesByPeerID = linkedMapOf<String, ArrayDeque<PendingPrivateMessage>>()
    private val typedPayloadsByPeerID = linkedMapOf<String, ArrayDeque<ByteArray>>()

    val isEmpty: Boolean
        get() = lock.withLock {
            privateMessagesByPeerID.isEmpty() && typedPayloadsByPeerID.isEmpty()
        }

    fun clear() = lock.withLock {
        privateMessagesByPeerID.clear()
        typedPayloadsByPeerID.clear()
    }

    fun appendPrivateMessage(content: String, messageID: String, peerID: String) = lock.withLock {
        ensurePeerCapacity(privateMessagesByPeerID)
        val queue = privateMessagesByPeerID.getOrPut(peerID) { ArrayDeque() }
        if (queue.size >= maxPrivateMessagesPerPeer) {
            queue.removeFirst()
        }
        queue.addLast(PendingPrivateMessage(content, messageID))
    }

    fun takePrivateMessages(peerID: String): List<PendingPrivateMessage> = lock.withLock {
        val queue = privateMessagesByPeerID.remove(peerID) ?: return emptyList()
        queue.toList()
    }

    fun prependPrivateMessages(messages: List<PendingPrivateMessage>, peerID: String) {
        lock.withLock {
            if (messages.isEmpty()) return
            ensurePeerCapacity(privateMessagesByPeerID)
            val queue = privateMessagesByPeerID.getOrPut(peerID) { ArrayDeque() }
            for (i in messages.indices.reversed()) {
                queue.addFirst(messages[i])
            }
            while (queue.size > maxPrivateMessagesPerPeer) {
                queue.removeLast()
            }
        }
    }

    fun appendTypedPayload(payload: ByteArray, peerID: String) = lock.withLock {
        ensurePeerCapacity(typedPayloadsByPeerID)
        val queue = typedPayloadsByPeerID.getOrPut(peerID) { ArrayDeque() }
        if (queue.size >= maxTypedPayloadsPerPeer) {
            queue.removeFirst()
        }
        queue.addLast(payload.copyOf())
    }

    fun takeTypedPayloads(peerID: String): List<ByteArray> = lock.withLock {
        val queue = typedPayloadsByPeerID.remove(peerID) ?: return emptyList()
        queue.toList()
    }

    private fun <T> ensurePeerCapacity(map: LinkedHashMap<String, ArrayDeque<T>>) {
        while (map.size >= maxPeers && map.isNotEmpty()) {
            val eldest = map.entries.first().key
            map.remove(eldest)
        }
    }

    companion object {
        const val DEFAULT_MAX_PRIVATE_PER_PEER = 32
        const val DEFAULT_MAX_TYPED_PER_PEER = 32
        const val DEFAULT_MAX_PEERS = 64
    }
}
