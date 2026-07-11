package com.app.database.dao

import com.app.common.AppDispatchers
import com.app.database.db.DatabaseManager
import kotlinx.coroutines.withContext

/** A prekey chosen to seal one message with: the owner-assigned id and its public key. */
class AssignedPrekey(val id: UInt, val publicKey: ByteArray)

/**
 * Persistent store of signature-verified one-time prekey bundles received from other peers
 * (`prekey_bundle` / `prekey_bundle_key` tables). Mirrors the reference iOS `PrekeyBundleStore`:
 * one bundle per owner Noise static key, strictly-newer `generatedAt` replaces, consumption state
 * for surviving IDs carries across replacements, LRU-capped owner count, and a freshness window for
 * sealing. Signature verification is the caller's — this store only guards resources and reuse.
 */
class PrekeyBundleDao(
    private val databaseManager: DatabaseManager,
    private val dispatchers: AppDispatchers,
) {

    object Limits {
        /** Bounded owner count, evicted LRU by `updated_at` (reference maxPeers). */
        const val MAX_PEERS = 200

        /**
         * Don't seal to bundles older than this: the owner may have rotated the unconsumed keys out
         * (reference `maxBundleAgeForSealingSeconds` = 7 days).
         */
        const val MAX_BUNDLE_AGE_FOR_SEALING_MS: Long = 7L * 24 * 60 * 60 * 1000
    }

    /**
     * Stores a bundle whose signature the caller already verified against the owner's announce-bound
     * signing key. Returns false when an equal-or-newer bundle is already cached (nothing changed).
     *
     * On replace, IDs this device already sealed with that the fresh bundle still offers stay marked
     * used (a top-up keeps the owner's unconsumed keys, so consumption state must survive); the rest
     * are dropped with their assignments.
     */
    suspend fun ingest(
        noiseKey: ByteArray,
        generatedAt: Long,
        prekeys: List<Pair<UInt, ByteArray>>,
        nowMs: Long,
    ): Boolean = withContext(dispatchers.io) {
        if (prekeys.isEmpty()) return@withContext false
        val queries = databaseManager.getDb().prekeyQueries
        queries.transactionWithResult {
            val existing = queries.selectBundle(noiseKey).executeAsOneOrNull()
            if (existing != null && existing.generated_at >= generatedAt) {
                return@transactionWithResult false
            }

            val newIds = prekeys.mapTo(HashSet()) { it.first.toLong() }
            val carried = queries.selectKeys(noiseKey).executeAsList()
                .filter { it.used == 1L && it.prekey_id in newIds }
                .associateBy { it.prekey_id }

            queries.deleteKeysForBundle(noiseKey)
            if (existing == null) {
                queries.insertBundle(noiseKey, generatedAt, nowMs)
            } else {
                queries.updateBundle(generatedAt, nowMs, noiseKey)
            }
            for ((id, publicKey) in prekeys) {
                val prior = carried[id.toLong()]
                queries.insertKey(
                    noise_key = noiseKey,
                    prekey_id = id.toLong(),
                    public_key = publicKey,
                    used = if (prior != null) 1L else 0L,
                    assigned_message_id = prior?.assigned_message_id,
                )
            }

            // Bounded owner count; replacing a known owner never triggers eviction of others'
            // fresher entries beyond the cap (oldest updated_at goes first).
            while (queries.countBundles().executeAsOne() > Limits.MAX_PEERS) {
                val victim = queries.selectAllBundles().executeAsList().firstOrNull()
                    ?: break
                queries.deleteKeysForBundle(victim.noise_key)
                queries.deleteBundle(victim.noise_key)
            }
            true
        }
    }

    /**
     * The prekey to seal [messageId] with: the message's existing assignment if any (re-deposits
     * reuse it), else the lowest unused ID, which is then marked used. Null when no fresh bundle is
     * cached or all its prekeys are spent — callers fall back to static (v1) sealing.
     */
    suspend fun assignPrekey(
        messageId: String,
        recipientNoiseKey: ByteArray,
        nowMs: Long,
    ): AssignedPrekey? = withContext(dispatchers.io) {
        val queries = databaseManager.getDb().prekeyQueries
        queries.transactionWithResult {
            val bundle = queries.selectBundle(recipientNoiseKey).executeAsOneOrNull()
                ?: return@transactionWithResult null
            if (!isFreshForSealing(bundle.generated_at, nowMs)) return@transactionWithResult null

            val keys = queries.selectKeys(recipientNoiseKey).executeAsList()
            keys.firstOrNull { it.assigned_message_id == messageId }?.let {
                return@transactionWithResult AssignedPrekey(it.prekey_id.toUInt(), it.public_key)
            }

            val fresh = keys.firstOrNull { it.used == 0L } ?: return@transactionWithResult null
            queries.markKeyUsed(messageId, recipientNoiseKey, fresh.prekey_id)
            queries.touchBundle(nowMs, recipientNoiseKey)
            AssignedPrekey(fresh.prekey_id.toUInt(), fresh.public_key)
        }
    }

    /** Whether an unexpired bundle with sealable prekeys is cached for [noiseKey]. */
    suspend fun hasUsableBundle(noiseKey: ByteArray, nowMs: Long): Boolean = withContext(dispatchers.io) {
        val queries = databaseManager.getDb().prekeyQueries
        queries.transactionWithResult {
            val bundle = queries.selectBundle(noiseKey).executeAsOneOrNull()
                ?: return@transactionWithResult false
            if (!isFreshForSealing(bundle.generated_at, nowMs)) return@transactionWithResult false
            queries.selectKeys(noiseKey).executeAsList().any { it.used == 0L }
        }
    }

    suspend fun bundleCount(): Long = withContext(dispatchers.io) {
        databaseManager.getDb().prekeyQueries.countBundles().executeAsOne()
    }

    /** Panic wipe: drop all cached bundles (the DB crypto-erase covers this transitively too). */
    suspend fun wipe() = withContext(dispatchers.io) {
        val queries = databaseManager.getDb().prekeyQueries
        queries.transaction {
            queries.deleteAllKeys()
            queries.deleteAllBundles()
        }
    }

    private fun isFreshForSealing(generatedAt: Long, nowMs: Long): Boolean =
        nowMs - generatedAt <= Limits.MAX_BUNDLE_AGE_FOR_SEALING_MS
}
