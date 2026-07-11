@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class, kotlin.time.ExperimentalTime::class)

package com.app.crypto.prekey

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import com.app.common.utils.Log
import com.app.crypto.noise.southernstorm.protocol.Noise
import com.app.crypto.secure.SecureKeyValueStore
import kotlin.io.encoding.Base64
import kotlin.time.Clock

/** One published one-time prekey: the owner-assigned sequential id and its Curve25519 public key. */
class PublicPrekey(val id: UInt, val publicKey: ByteArray)

/**
 * Owns this device's one-time Curve25519 prekey private keys, mirroring the reference iOS
 * `LocalPrekeyStore`.
 *
 * Privates persist behind the [SecureKeyValueStore] seam (single encrypted entry, same protection
 * as the identity keys). A batch of [Policy.BATCH_SIZE] unconsumed prekeys backs the gossiped
 * bundle; when consumption drops the unconsumed count below [Policy.REPLENISH_THRESHOLD], the
 * batch tops back up and the bundle's `generatedAt` bumps so peers replace their cached copy.
 *
 * Redelivery grace: spray-and-wait means the same prekey-sealed ciphertext can arrive via several
 * couriers days apart. A consumed prekey's private key is therefore retained for
 * [Policy.CONSUMED_GRACE_MS] after first use and only then deleted. The forward-secrecy clock
 * starts at deletion, not at first open — the recipient cannot distinguish a redelivery from a
 * fresh ciphertext, so the window is kept short and fixed.
 */
internal class LocalPrekeyStore(
    private val store: SecureKeyValueStore,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    object Policy {
        /** = PrekeyBundle.maxPrekeys in the reference. */
        const val BATCH_SIZE = 8

        /** Top the batch back up when the unconsumed count drops below this. */
        const val REPLENISH_THRESHOLD = 3

        /** How long a consumed prekey private survives for duplicate courier deliveries: 48 h. */
        const val CONSUMED_GRACE_MS: Long = 48L * 60 * 60 * 1000

        /**
         * Unconsumed prekeys older than this are rotated out: no honest sender seals to a bundle
         * that stale (see the bundle store's 7-day sealing freshness): 30 days.
         */
        const val UNCONSUMED_RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000
    }

    private class Record(
        val id: UInt,
        val privateKey: ByteArray,
        val createdAt: Long,
        var consumedAt: Long?,
    )

    private val lock = Lock()

    // Guarded by `lock`.
    private var records = mutableListOf<Record>()
    private var nextId: UInt = 0u
    private var generatedAt: ULong = 0uL
    private var loaded = false

    /**
     * Unconsumed public prekeys for the gossiped bundle (derived from the stored privates),
     * generating the initial batch on first use. Sorted by id for canonical signing bytes.
     */
    fun currentBundlePrekeys(): Pair<List<PublicPrekey>, ULong> = lock.withLock {
        loadLocked()
        replenishLocked()
        val prekeys = records
            .filter { it.consumedAt == null }
            .sortedBy { it.id }
            .map { PublicPrekey(it.id, derivePublicKey(it.privateKey)) }
        prekeys to generatedAt
    }

    /** Private key for a prekey id: unconsumed, or consumed within the redelivery grace window. */
    fun privateKey(id: UInt): ByteArray? = lock.withLock {
        loadLocked()
        val record = records.firstOrNull { it.id == id } ?: return null
        val consumedAt = record.consumedAt
        if (consumedAt != null && nowMs() - consumedAt > Policy.CONSUMED_GRACE_MS) return null
        record.privateKey.copyOf()
    }

    /**
     * Marks a prekey consumed (starts its grace clock). Idempotent: a redelivery within the grace
     * window does not restart the clock.
     *
     * Returns true when this call actually retired a prekey, i.e. the published bundle shrank.
     * `generatedAt` advances strictly so peers accept the shrunken replacement (their monotonic
     * ingest rejects a same-`generatedAt` bundle and they would keep assigning the consumed id).
     * The caller re-gossips on a true result.
     */
    fun markConsumed(id: UInt): Boolean = lock.withLock {
        loadLocked()
        val record = records.firstOrNull { it.id == id && it.consumedAt == null } ?: return false
        record.consumedAt = nowMs()
        advanceGeneratedAtLocked()
        persistLocked()
        true
    }

    /**
     * Prunes dead prekeys and tops the unconsumed batch back up when it runs low. Returns true
     * when the published bundle changed (caller should re-gossip).
     */
    fun replenishIfNeeded(): Boolean = lock.withLock {
        loadLocked()
        replenishLocked()
    }

    fun unconsumedCount(): Int = lock.withLock {
        loadLocked()
        records.count { it.consumedAt == null }
    }

    /** Panic wipe: zeroize all prekey privates in memory and drop the persisted entry. */
    fun wipe() = lock.withLock {
        records.forEach { it.privateKey.fill(0) }
        records.clear()
        nextId = 0u
        generatedAt = 0uL
        loaded = true
        store.remove(STORE_KEY)
    }

    // MARK: - Internals (call only under `lock`)

    private fun replenishLocked(): Boolean {
        val now = nowMs()

        // Consumed prekeys past the grace window are gone for good; stale unconsumed ones rotate
        // out (their bundle is too old to seal to). Zeroize before dropping.
        val unconsumedBefore = records.count { it.consumedAt == null }
        val recordsBefore = records.size
        val (dead, alive) = records.partition { record ->
            val consumedAt = record.consumedAt
            if (consumedAt != null) now - consumedAt > Policy.CONSUMED_GRACE_MS
            else now - record.createdAt > Policy.UNCONSUMED_RETENTION_MS
        }
        dead.forEach { it.privateKey.fill(0) }
        records = alive.toMutableList()

        // Only a change to the *unconsumed* set alters the published bundle; grace-expired
        // consumed keys were never in it.
        val unconsumed = records.count { it.consumedAt == null }
        var bundleChanged = unconsumed != unconsumedBefore

        if (unconsumed < Policy.REPLENISH_THRESHOLD) {
            repeat(Policy.BATCH_SIZE - unconsumed) {
                records.add(Record(nextId, generatePrivateKey(), now, null))
                nextId++
            }
            advanceGeneratedAtLocked()
            bundleChanged = true
            Log.d(TAG, "Replenished one-time prekeys (unconsumed was $unconsumed)")
        }

        if (bundleChanged || records.size != recordsBefore) persistLocked()
        return bundleChanged
    }

    /**
     * Advance `generatedAt` strictly monotonically: wall-clock millis, but never repeated or
     * regressing, so two changes within one millisecond still produce increasing stamps that
     * peers' monotonic ingest accepts.
     */
    private fun advanceGeneratedAtLocked() {
        val now = maxOf(0L, nowMs()).toULong()
        generatedAt = maxOf(now, generatedAt + 1uL)
    }

    private fun generatePrivateKey(): ByteArray {
        val dh = Noise.createDH("25519")
        try {
            dh.generateKeyPair()
            val privateKey = ByteArray(32)
            dh.getPrivateKey(privateKey, 0)
            return privateKey
        } finally {
            dh.destroy()
        }
    }

    private fun derivePublicKey(privateKey: ByteArray): ByteArray {
        val dh = Noise.createDH("25519")
        try {
            dh.setPrivateKey(privateKey, 0)
            val publicKey = ByteArray(32)
            dh.getPublicKey(publicKey, 0)
            return publicKey
        } finally {
            dh.destroy()
        }
    }

    // MARK: - Persistence
    //
    // Single encrypted entry, mirroring the reference's single keychain blob. Explicit text
    // format (no serialization dependency): header `nextId:generatedAt`, then one record per
    // line as `id:privateKeyBase64:createdAt:consumedAt` (consumedAt empty when unconsumed).

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        val raw = store.getString(STORE_KEY) ?: return
        try {
            val lines = raw.split('\n')
            val header = lines.first().split(':')
            val parsed = lines.drop(1).filter { it.isNotEmpty() }.map { line ->
                val parts = line.split(':')
                Record(
                    id = parts[0].toUInt(),
                    privateKey = Base64.decode(parts[1]),
                    createdAt = parts[2].toLong(),
                    consumedAt = parts[3].ifEmpty { null }?.toLong(),
                )
            }
            records = parsed.toMutableList()
            nextId = header[0].toUInt()
            generatedAt = header[1].toULong()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode prekey store, starting fresh: ${e.message}")
            records = mutableListOf()
            nextId = 0u
            generatedAt = 0uL
        }
    }

    private fun persistLocked() {
        val text = buildString {
            append(nextId).append(':').append(generatedAt)
            for (record in records) {
                append('\n')
                append(record.id).append(':')
                append(Base64.encode(record.privateKey)).append(':')
                append(record.createdAt).append(':')
                record.consumedAt?.let(::append)
            }
        }
        try {
            store.putString(STORE_KEY, text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist prekey store: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "LocalPrekeyStore"
        private const val STORE_KEY = "one_time_prekeys_v1"
    }
}
