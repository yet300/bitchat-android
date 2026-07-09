package com.app.database.dao

import com.app.common.AppDispatchers
import com.app.database.Outbox_envelope
import com.app.database.db.DatabaseManager
import kotlinx.coroutines.withContext

/**
 * Persistent send-outbox access. Rows are the projection of an `OutgoingEnvelope` (the lossless copy
 * lives in `payload_json`); the data layer owns the envelope<->row mapping. Drain is transactional so
 * a peer's queue is read and cleared atomically, matching the old in-memory `remove`-and-return.
 */
class OutboxDao(
    private val databaseManager: DatabaseManager,
    private val dispatchers: AppDispatchers,
) {

    /** Enqueue one envelope, then trim the oldest entries beyond [cap] (0 disables the cap). */
    suspend fun insert(
        peerId: String,
        kind: String,
        payloadJson: String,
        messageId: String?,
        createdAt: Long,
        expiryAt: Long?,
        cap: Long,
    ) = withContext(dispatchers.io) {
        val queries = databaseManager.getDb().outboxEnvelopeQueries
        queries.transaction {
            queries.insert(
                peer_id = peerId,
                kind = kind,
                payload_json = payloadJson,
                message_id = messageId,
                created_at = createdAt,
                expiry_at = expiryAt,
                attempts = 0L,
                recipient_tag = null,
                ciphertext = null,
                copies = null,
                prekey_id = null,
            )
            if (cap > 0) queries.deleteOldestBeyondCap(cap)
        }
    }

    /**
     * Purge expired rows (as of [nowMillis]) then read-and-delete everything queued for [peerId],
     * returning it in insertion (FIFO) order. Empty list if nothing was queued.
     */
    suspend fun drainByPeer(peerId: String, nowMillis: Long): List<Outbox_envelope> =
        withContext(dispatchers.io) {
            val queries = databaseManager.getDb().outboxEnvelopeQueries
            queries.transactionWithResult {
                queries.deleteExpired(nowMillis)
                val rows = queries.selectByPeer(peerId).executeAsList()
                queries.deleteByPeer(peerId)
                rows
            }
        }

    /** Distinct peer keys with at least one queued envelope. */
    suspend fun peers(): List<String> = withContext(dispatchers.io) {
        databaseManager.getDb().outboxEnvelopeQueries.selectPeers().executeAsList()
    }

    suspend fun count(): Long = withContext(dispatchers.io) {
        databaseManager.getDb().outboxEnvelopeQueries.countAll().executeAsOne()
    }

    suspend fun deleteExpired(nowMillis: Long) = withContext(dispatchers.io) {
        databaseManager.getDb().outboxEnvelopeQueries.deleteExpired(nowMillis)
    }
}
