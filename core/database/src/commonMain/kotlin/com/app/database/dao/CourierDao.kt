package com.app.database.dao

import com.app.common.encoding.hexEncodedString
import com.app.database.Courier_envelope
import com.app.database.db.DatabaseManager
import kotlinx.coroutines.withContext

/** A courier deposit's trust tier, decided by the caller's policy (mutual favorite vs verified). */
enum class CourierTier(val wire: String) {
    FAVORITE("favorite"),
    VERIFIED("verified");

    companion object {
        fun fromWire(value: String): CourierTier = entries.firstOrNull { it.wire == value } ?: FAVORITE
    }
}

/** An envelope carried for a third party plus the split budget it should be handed with. */
data class SprayCopy(val row: Courier_envelope, val copies: Int)

/**
 * Persistent store of opaque courier envelopes this device carries for offline third parties
 * (`courier_envelope` table). Enforces the resource bounds (total / per-depositor / per-tier caps,
 * eviction, idempotency, expiry pruning) inside atomic transactions so racing deposits and handovers
 * stay consistent; the store is small (≤ [Limits.MAX_ENVELOPES]), so decision logic runs in-Kotlin
 * over the loaded rows. Trust policy (which depositor gets which tier, or none) is the caller's — this
 * store only guards resources, matching the reference iOS `CourierStore`.
 */
class CourierDao(
    private val databaseManager: DatabaseManager,
    private val dispatchers: com.app.common.AppDispatchers,
) {

    object Limits {
        const val MAX_ENVELOPES = 40
        /** Verified-tier mail can never crowd out favorites' share. */
        const val MAX_VERIFIED_ENVELOPES = 20
        const val MAX_PER_FAVORITE_DEPOSITOR = 5
        const val MAX_PER_VERIFIED_DEPOSITOR = 2
    }

    /**
     * Accept an envelope from a depositor under its resource quota. Returns false when a quota or the
     * total cap rejects it. Callers validate envelope shape/expiry-ceiling before calling; this method
     * enforces only counts and eviction.
     *
     * Idempotent by ciphertext: a re-deposit of the same envelope keeps the larger copy budget.
     * Eviction on a full store sheds the oldest verified-tier mail first, then (only for a favorite
     * deposit) the oldest favorite; a verified deposit never displaces a favorite.
     */
    suspend fun deposit(
        recipientTag: ByteArray,
        expiry: Long,
        ciphertext: ByteArray,
        depositorNoiseKey: ByteArray,
        storedAt: Long,
        tier: CourierTier,
        copies: Int,
        prekeyId: Long?,
        nowMs: Long,
    ): Boolean = withContext(dispatchers.io) {
        val queries = databaseManager.getDb().courierStoreQueries
        queries.transactionWithResult {
            queries.deleteExpired(nowMs)
            val rows = queries.selectAll().executeAsList()

            // Identical ciphertext is the same envelope; accept idempotently, keeping the larger budget.
            rows.firstOrNull { it.ciphertext.contentEquals(ciphertext) }?.let { existing ->
                val merged = maxOf(existing.copies, copies.toLong())
                if (merged != existing.copies) {
                    queries.updateCopiesAndSpray(merged, existing.sprayed_to, existing.id)
                }
                return@transactionWithResult true
            }

            val perDepositorLimit =
                if (tier == CourierTier.FAVORITE) Limits.MAX_PER_FAVORITE_DEPOSITOR
                else Limits.MAX_PER_VERIFIED_DEPOSITOR
            if (rows.count { it.depositor_noise_key.contentEquals(depositorNoiseKey) } >= perDepositorLimit) {
                return@transactionWithResult false
            }
            if (tier == CourierTier.VERIFIED &&
                rows.count { it.tier == CourierTier.VERIFIED.wire } >= Limits.MAX_VERIFIED_ENVELOPES
            ) {
                return@transactionWithResult false
            }
            if (rows.size >= Limits.MAX_ENVELOPES) {
                val oldestVerified = rows.firstOrNull { it.tier == CourierTier.VERIFIED.wire }
                when {
                    oldestVerified != null -> queries.deleteById(oldestVerified.id)
                    tier == CourierTier.FAVORITE -> queries.deleteById(rows.first().id)
                    // Store full of favorite-tier mail: a verified deposit is rejected, not honoured.
                    else -> return@transactionWithResult false
                }
            }

            queries.insert(
                recipient_tag = recipientTag,
                expiry = expiry,
                ciphertext = ciphertext,
                depositor_noise_key = depositorNoiseKey,
                stored_at = storedAt,
                tier = tier.wire,
                copies = copies.toLong(),
                sprayed_to = "",
                last_remote_handover_at = null,
                prekey_id = prekeyId,
            )
            true
        }
    }

    /**
     * Remove and return all (non-expired) envelopes whose recipient tag matches [candidateTags] —
     * the destructive handover to a recipient we met directly. The depositor's outbox still retains
     * the original for direct delivery, so an optimistic take is safe.
     */
    suspend fun takeByTags(candidateTags: List<ByteArray>, nowMs: Long): List<Courier_envelope> =
        withContext(dispatchers.io) {
            val queries = databaseManager.getDb().courierStoreQueries
            queries.transactionWithResult {
                queries.deleteExpired(nowMs)
                val matched = queries.selectAll().executeAsList()
                    .filter { row -> candidateTags.any { it.contentEquals(row.recipient_tag) } }
                matched.forEach { queries.deleteById(it.id) }
                matched
            }
        }

    /**
     * Envelopes addressed to a recipient heard only via a *relayed* announce. Non-destructive: a
     * multi-hop send is speculative, so the envelope stays carried. The per-envelope [cooldownMs]
     * keeps repeated announces from re-flooding the mesh.
     */
    suspend fun remoteHandover(
        candidateTags: List<ByteArray>,
        cooldownMs: Long,
        nowMs: Long,
    ): List<Courier_envelope> = withContext(dispatchers.io) {
        val queries = databaseManager.getDb().courierStoreQueries
        queries.transactionWithResult {
            queries.deleteExpired(nowMs)
            val matched = queries.selectAll().executeAsList().filter { row ->
                candidateTags.any { it.contentEquals(row.recipient_tag) } &&
                    (row.last_remote_handover_at?.let { nowMs - it >= cooldownMs } ?: true)
            }
            matched.forEach { queries.updateRemoteHandoverAt(nowMs, it.id) }
            matched
        }
    }

    /**
     * Spray-and-wait: envelopes to re-deposit with a courier just encountered, each with half its
     * remaining budget (binary spray). Skips envelopes the courier deposited, envelopes addressed to
     * them ([courierTags]), carry-only envelopes, and couriers already sprayed. Returns each envelope
     * with the copy count to hand over.
     */
    suspend fun spray(
        courierNoiseKey: ByteArray,
        courierTags: List<ByteArray>,
        nowMs: Long,
    ): List<SprayCopy> = withContext(dispatchers.io) {
        val queries = databaseManager.getDb().courierStoreQueries
        val courierHex = courierNoiseKey.hexEncodedString()
        queries.transactionWithResult {
            queries.deleteExpired(nowMs)
            val out = ArrayList<SprayCopy>()
            for (row in queries.selectAll().executeAsList()) {
                val sprayed = decodeSprayed(row.sprayed_to)
                if (row.copies <= 1L) continue
                if (row.depositor_noise_key.contentEquals(courierNoiseKey)) continue
                if (courierHex in sprayed) continue
                if (courierTags.any { it.contentEquals(row.recipient_tag) }) continue
                val given = (row.copies / 2).toInt()
                val remaining = row.copies - given
                queries.updateCopiesAndSpray(remaining, encodeSprayed(sprayed + courierHex), row.id)
                out.add(SprayCopy(row, given))
            }
            out
        }
    }

    /** Prune expired mail and return the carried count. */
    suspend fun count(nowMs: Long): Long = withContext(dispatchers.io) {
        val queries = databaseManager.getDb().courierStoreQueries
        queries.transactionWithResult {
            queries.deleteExpired(nowMs)
            queries.countAll().executeAsOne()
        }
    }

    /** Panic wipe: drop all carried mail. */
    suspend fun wipe() = withContext(dispatchers.io) {
        databaseManager.getDb().courierStoreQueries.deleteAll()
    }

    private fun decodeSprayed(value: String): Set<String> =
        if (value.isEmpty()) emptySet() else value.split(',').toSet()

    private fun encodeSprayed(keys: Set<String>): String = keys.joinToString(",")
}
