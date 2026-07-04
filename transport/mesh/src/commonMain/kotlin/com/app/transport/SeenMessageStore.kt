package com.app.transport

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import com.app.common.utils.Log
import com.app.crypto.identity.SecureIdentityStateManager
import com.app.common.serialization.JsonConfig
import kotlinx.serialization.Serializable

/**
 * Persistent store for message IDs we've already acknowledged (DELIVERED) or READ.
 * Limits to last MAX_IDS entries per set to avoid memory bloat.
 */
class SeenMessageStore(private val secure: SecureIdentityStateManager) {
    companion object {
        private const val TAG = "SeenMessageStore"
        private const val STORAGE_KEY = "seen_message_store_v1"
        private const val MAX_IDS = 10_000
    }

    private val lock = Lock()
    private val delivered = LinkedHashSet<String>(MAX_IDS)
    private val read = LinkedHashSet<String>(MAX_IDS)

    init { load() }

    fun hasDelivered(id: String) = lock.withLock { delivered.contains(id) }
    fun hasRead(id: String) = lock.withLock { read.contains(id) }

    fun markDelivered(id: String) = lock.withLock {
        if (delivered.remove(id)) delivered.add(id) else {
            delivered.add(id)
            trim(delivered)
        }
        persist()
    }

    fun markRead(id: String) = lock.withLock {
        if (read.remove(id)) read.add(id) else {
            read.add(id)
            trim(read)
        }
        persist()
    }

    /** Marks many ids read with a single persist (conversation-level mark-read). */
    fun markReadAll(ids: Collection<String>) = lock.withLock {
        if (ids.isEmpty()) return@withLock
        ids.forEach { id ->
            if (read.remove(id)) read.add(id) else read.add(id)
        }
        trim(read)
        persist()
    }

    fun clear() = lock.withLock {
        delivered.clear()
        read.clear()
        persist()
    }

    private fun trim(set: LinkedHashSet<String>) {
        if (set.size <= MAX_IDS) return
        val it = set.iterator()
        while (set.size > MAX_IDS && it.hasNext()) {
            it.next(); it.remove()
        }
    }

    private fun load() = lock.withLock {
        try {
            val json = secure.getSecureValue(STORAGE_KEY) ?: return@withLock
            val data = JsonConfig.json.decodeFromString(StorePayload.serializer(), json)
            delivered.clear(); read.clear()
            data.delivered.takeLast(MAX_IDS).forEach { delivered.add(it) }
            data.read.takeLast(MAX_IDS).forEach { read.add(it) }
            Log.d(TAG, "Loaded delivered=${delivered.size}, read=${read.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load SeenMessageStore: ${e.message}")
        }
    }

    private fun persist() {
        try {
            val payload = StorePayload(delivered.toList(), read.toList())
            val json = JsonConfig.json.encodeToString(StorePayload.serializer(), payload)
            secure.storeSecureValue(STORAGE_KEY, json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist SeenMessageStore: ${e.message}")
        }
    }

    @Serializable
    private data class StorePayload(
        val delivered: List<String> = emptyList(),
        val read: List<String> = emptyList()
    )
}
