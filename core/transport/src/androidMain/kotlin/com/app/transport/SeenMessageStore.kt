package com.app.transport

import com.app.common.utils.Log
import com.app.crypto.identity.SecureIdentityStateManager
import com.app.common.serialization.JsonConfig
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable

/**
 * Persistent store for message IDs we've already acknowledged (DELIVERED) or READ.
 * Limits to last MAX_IDS entries per set to avoid memory bloat.
 */
@SingleIn(AppScope::class)
@Inject
class SeenMessageStore(private val secure: SecureIdentityStateManager) {
    companion object {
        private const val TAG = "SeenMessageStore"
        private const val STORAGE_KEY = "seen_message_store_v1"
        private const val MAX_IDS = 10_000
    }

    private val delivered = LinkedHashSet<String>(MAX_IDS)
    private val read = LinkedHashSet<String>(MAX_IDS)

    init { load() }

    @Synchronized fun hasDelivered(id: String) = delivered.contains(id)
    @Synchronized fun hasRead(id: String) = read.contains(id)

    @Synchronized fun markDelivered(id: String) {
        if (delivered.remove(id)) delivered.add(id) else {
            delivered.add(id)
            trim(delivered)
        }
        persist()
    }

    @Synchronized fun markRead(id: String) {
        if (read.remove(id)) read.add(id) else {
            read.add(id)
            trim(read)
        }
        persist()
    }

    /** Marks many ids read with a single persist (conversation-level mark-read). */
    @Synchronized fun markReadAll(ids: Collection<String>) {
        if (ids.isEmpty()) return
        ids.forEach { id ->
            if (read.remove(id)) read.add(id) else read.add(id)
        }
        trim(read)
        persist()
    }

    @Synchronized fun clear() {
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

    @Synchronized private fun load() {
        try {
            val json = secure.getSecureValue(STORAGE_KEY) ?: return
            val data = JsonConfig.json.decodeFromString(StorePayload.serializer(), json)
            delivered.clear(); read.clear()
            data.delivered.takeLast(MAX_IDS).forEach { delivered.add(it) }
            data.read.takeLast(MAX_IDS).forEach { read.add(it) }
            Log.d(TAG, "Loaded delivered=${delivered.size}, read=${read.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load SeenMessageStore: ${e.message}")
        }
    }

    @Synchronized private fun persist() {
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
