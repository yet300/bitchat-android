package com.app.database.dao

import com.app.common.AppDispatchers
import com.app.database.db.DatabaseManager
import kotlinx.coroutines.withContext

/** One accepted vouch: [voucherFingerprint] attested, at [timestampMs], to having verified a peer. */
data class VouchRecord(
    val voucherFingerprint: String,
    val timestampMs: Long,
)

/**
 * Persistent store of accepted vouch attestations, keyed by vouchee fingerprint.
 *
 * Enforces only the per-vouchee cap. The trust gates that need the verified set (voucher must be
 * verified, vouchee must not be, attestation must be inside its validity window) live in the data
 * layer, which owns that state.
 */
class VouchDao(
    private val databaseManager: DatabaseManager,
    private val dispatchers: AppDispatchers,
) {

    /**
     * Records a vouch, keeping at most [MAX_VOUCHERS_PER_VOUCHEE] vouchers per vouchee (most recent
     * retained). Returns true when the store changed.
     *
     * An existing entry for the same voucher is refreshed to the newer of the two timestamps —
     * never regressed, so a replayed stale attestation cannot age out a fresh one. When the cap is
     * full, the new vouch displaces the oldest only if it is strictly newer; ties are rejected so
     * the outcome does not depend on sort stability (the reference's Swift sort is unstable here).
     */
    suspend fun record(
        voucheeFingerprint: String,
        voucherFingerprint: String,
        timestampMs: Long,
    ): Boolean = withContext(dispatchers.io) {
        val queries = databaseManager.getDb().vouchQueries
        queries.transactionWithResult {
            val existing = queries.selectOne(voucheeFingerprint, voucherFingerprint).executeAsOneOrNull()
            if (existing != null) {
                val newest = maxOf(existing.timestamp_ms, timestampMs)
                queries.upsert(voucheeFingerprint, voucherFingerprint, newest)
                return@transactionWithResult true
            }

            val count = queries.countByVouchee(voucheeFingerprint).executeAsOne()
            if (count < MAX_VOUCHERS_PER_VOUCHEE) {
                queries.upsert(voucheeFingerprint, voucherFingerprint, timestampMs)
                return@transactionWithResult true
            }

            val oldest = queries.selectOldestByVouchee(voucheeFingerprint).executeAsOneOrNull()
                ?: return@transactionWithResult false
            if (timestampMs <= oldest.timestamp_ms) {
                // Full of fresher vouches; nothing changed.
                return@transactionWithResult false
            }
            queries.deleteOne(voucheeFingerprint, oldest.voucher_fingerprint)
            queries.upsert(voucheeFingerprint, voucherFingerprint, timestampMs)
            true
        }
    }

    /** All stored vouchers for a vouchee, most recent first. Validity is the caller's to recompute. */
    suspend fun vouchersFor(voucheeFingerprint: String): List<VouchRecord> = withContext(dispatchers.io) {
        databaseManager.getDb().vouchQueries
            .selectByVouchee(voucheeFingerprint)
            .executeAsList()
            .map { VouchRecord(it.voucher_fingerprint, it.timestamp_ms) }
    }

    /** Every stored vouch, grouped by vouchee fingerprint. */
    suspend fun allByVouchee(): Map<String, List<VouchRecord>> = withContext(dispatchers.io) {
        databaseManager.getDb().vouchQueries
            .selectAll()
            .executeAsList()
            .groupBy(
                keySelector = { it.vouchee_fingerprint },
                valueTransform = { VouchRecord(it.voucher_fingerprint, it.timestamp_ms) },
            )
            .mapValues { (_, records) -> records.sortedByDescending(VouchRecord::timestampMs) }
    }

    suspend fun deleteByVouchee(voucheeFingerprint: String) = withContext(dispatchers.io) {
        databaseManager.getDb().vouchQueries.deleteByVouchee(voucheeFingerprint)
    }

    /** Panic wipe. */
    suspend fun deleteAll() = withContext(dispatchers.io) {
        databaseManager.getDb().vouchQueries.deleteAll()
    }

    companion object {
        /** Maximum vouchers retained per vouchee (most recent kept). Matches the reference. */
        const val MAX_VOUCHERS_PER_VOUCHEE = 8L
    }
}
