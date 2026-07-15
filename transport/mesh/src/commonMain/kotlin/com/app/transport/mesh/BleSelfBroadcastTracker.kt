package com.app.transport.mesh

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import com.app.common.encoding.toHexString
import com.app.transport.protocol.BitchatPacket

/** One-shot mapping from a relayed self-broadcast to the local timeline UUID. */
internal class BleSelfBroadcastTracker(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private data class Entry(val messageID: String, val sentAtMillis: Long)

    private val lock = Lock()
    private val entriesByDedupID = linkedMapOf<String, Entry>()

    init {
        require(capacity > 0)
    }

    fun record(messageID: String, packet: BitchatPacket, sentAtMillis: Long): Unit = lock.withLock {
        val key = dedupID(packet)
        entriesByDedupID.remove(key)
        entriesByDedupID[key] = Entry(messageID, sentAtMillis)
        while (entriesByDedupID.size > capacity) {
            entriesByDedupID.remove(entriesByDedupID.keys.first())
        }
    }

    fun takeMessageID(packet: BitchatPacket): String? = lock.withLock {
        entriesByDedupID.remove(dedupID(packet))?.messageID
    }

    fun prune(beforeMillis: Long): Unit = lock.withLock {
        val iterator = entriesByDedupID.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.sentAtMillis < beforeMillis) iterator.remove()
        }
    }

    fun clear(): Unit = lock.withLock {
        entriesByDedupID.clear()
    }

    internal fun debugCount(): Int = lock.withLock { entriesByDedupID.size }

    private fun dedupID(packet: BitchatPacket): String =
        "${packet.senderID.toHexString()}-${packet.timestamp}-${packet.type}"

    private companion object {
        const val DEFAULT_CAPACITY = 1_000
    }
}
