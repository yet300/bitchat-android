package com.app.transport

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import com.app.common.AppDispatchers
import com.app.common.utils.Log
import com.app.crypto.identity.SecureIdentityStateManager
import com.app.common.serialization.JsonConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Persistent store for message IDs we've already acknowledged (DELIVERED) or READ.
 * Limits to last MAX_IDS entries per set to avoid memory bloat.
 */
class SeenMessageStore(
    private val secure: SecureIdentityStateManager,
    dispatchers: AppDispatchers = AppDispatchers(),
) {
    companion object {
        private const val TAG = "SeenMessageStore"
        private const val STORAGE_KEY = "seen_message_store_v1"
        private const val MAX_IDS = 10_000

        // Debounce window for coalescing receipt-ACK writes. Each markDelivered/markRead only
        // updates the in-memory set synchronously and signals; the actual ~0.5 MB JSON encode +
        // Keystore-encrypted write happens at most once per window. Losing the last window on a
        // hard process kill is acceptable (this is a dedup cache for receipts, not source data);
        // flush() forces a synchronous write on graceful shutdown.
        private const val PERSIST_DEBOUNCE_MS = 3_000L
    }

    // Guards only the in-memory sets; JSON encoding and the secure-storage write happen
    // outside it (see persist()) so hasDelivered/hasRead on the receive path never wait
    // on storage IO.
    private val lock = Lock()
    private val delivered = LinkedHashSet<String>(MAX_IDS)
    private val read = LinkedHashSet<String>(MAX_IDS)
    private var version = 0L

    // Serializes storage writes; the version check keeps concurrent persists
    // last-writer-wins so an older snapshot never overwrites a newer one.
    private val persistLock = Lock()
    private var persistedVersion = 0L

    // Debounced persistence: a mutation signals here; a single worker coroutine coalesces a burst
    // of receipt ACKs (CONFLATED → N signals collapse to one) and writes the latest snapshot at
    // most once per PERSIST_DEBOUNCE_MS. Crucially this moves the encode+write off the caller's
    // thread — markReadAll runs under AppStateStore's ingest monitor (audit A9), and doing the
    // heavy write inline there stalled all message ingest.
    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())
    private val persistSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        load()
        scope.launch {
            for (signal in persistSignal) {
                delay(PERSIST_DEBOUNCE_MS)
                persist()
            }
        }
    }

    private fun schedulePersist() {
        persistSignal.trySend(Unit)
    }

    /**
     * Force a synchronous write of the latest snapshot, bypassing the debounce. Wire this into the
     * graceful-shutdown hook (AppShutdownCoordinator) so the last window of receipts is not lost.
     */
    fun flush() = persist()

    /** Flush and stop the debounce worker. */
    fun close() {
        flush()
        persistSignal.close()
        scope.cancel()
    }

    fun hasDelivered(id: String) = lock.withLock { delivered.contains(id) }
    fun hasRead(id: String) = lock.withLock { read.contains(id) }

    fun markDelivered(id: String) {
        lock.withLock {
            if (delivered.remove(id)) delivered.add(id) else {
                delivered.add(id)
                trim(delivered)
            }
            version++
        }
        schedulePersist()
    }

    fun markRead(id: String) {
        lock.withLock {
            if (read.remove(id)) read.add(id) else {
                read.add(id)
                trim(read)
            }
            version++
        }
        schedulePersist()
    }

    /** Marks many ids read with a single persist (conversation-level mark-read). */
    fun markReadAll(ids: Collection<String>) {
        if (ids.isEmpty()) return
        lock.withLock {
            ids.forEach { id ->
                read.remove(id)
                read.add(id)
            }
            trim(read)
            version++
        }
        schedulePersist()
    }

    fun clear() {
        lock.withLock {
            delivered.clear()
            read.clear()
            version++
        }
        // Security-sensitive wipe (panic/reset): persist the cleared state immediately rather
        // than waiting on the debounce, so a crash right after can't resurrect the old ids.
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
        val (payload, snapshotVersion) = lock.withLock {
            StorePayload(delivered.toList(), read.toList()) to version
        }
        persistLock.withLock {
            if (snapshotVersion <= persistedVersion) return
            try {
                val json = JsonConfig.json.encodeToString(StorePayload.serializer(), payload)
                secure.storeSecureValue(STORAGE_KEY, json)
                persistedVersion = snapshotVersion
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist SeenMessageStore: ${e.message}")
            }
        }
    }

    @Serializable
    private data class StorePayload(
        val delivered: List<String> = emptyList(),
        val read: List<String> = emptyList()
    )
}
