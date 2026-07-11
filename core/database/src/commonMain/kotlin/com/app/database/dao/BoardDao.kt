package com.app.database.dao

import com.app.common.AppDispatchers
import com.app.database.Board_entry
import com.app.database.db.DatabaseManager
import kotlinx.coroutines.withContext

/** Outcome of feeding a board packet into the store, so the mesh can decide whether to keep relaying. */
enum class BoardIngestResult { ACCEPTED, DUPLICATE, REJECTED }

/**
 * Persistent store of geohash bulletin-board posts + tombstones (`board_entry` table). Boards ingest
 * content from ANY node, so all anti-spam bounds live here and run inside atomic transactions
 * (racing ingests stay consistent). Transport-free — the data layer decodes/validates the wire and
 * passes primitives. Mirrors the reference iOS `BoardStore` ingest logic; the caller must have
 * verified the signature already.
 */
class BoardDao(
    private val databaseManager: DatabaseManager,
    private val dispatchers: AppDispatchers,
) {

    object Limits {
        const val MAX_POSTS = 200
        const val MAX_POSTS_PER_AUTHOR = 5
        /** A tombstone for a post we never saw: cap at the max post lifetime. */
        const val ORPHAN_TOMBSTONE_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000
        /** Orphan tombstones are sender-controlled volume, so cap them like posts. */
        const val MAX_ORPHAN_TOMBSTONES = 100
        const val MAX_ORPHAN_TOMBSTONES_PER_AUTHOR = 5
        /** Allowance for clock skew between peers when judging received timestamps. */
        const val CLOCK_SKEW_MS = 60L * 60 * 1000
    }

    private companion object {
        const val KIND_POST = 1L
        const val KIND_TOMBSTONE = 2L
    }

    /**
     * Ingest a post. Rejects an expired / future-dated / over-long-lifetime post (receive-time
     * sanity), a post already tombstoned, and dedups by post id; then appends and enforces the
     * per-author (5) then global (200) caps, evicting oldest-`createdAt` first. A locally-evicted
     * post is still ACCEPTED (valid mesh-wide, worth relaying).
     */
    suspend fun ingestPost(
        postId: ByteArray,
        authorKey: ByteArray,
        geohash: String,
        content: String,
        nickname: String,
        createdAt: Long,
        expiresAt: Long,
        flags: Long,
        signature: ByteArray,
        nowMs: Long,
    ): BoardIngestResult = withContext(dispatchers.io) {
        val q = databaseManager.getDb().boardQueries
        q.transactionWithResult {
            q.deleteExpiredPosts(nowMs)
            q.deleteExpiredTombstones(nowMs)

            if (expiresAt <= nowMs) return@transactionWithResult BoardIngestResult.REJECTED
            if (createdAt > nowMs + Limits.CLOCK_SKEW_MS) return@transactionWithResult BoardIngestResult.REJECTED
            if (expiresAt > nowMs + Limits.ORPHAN_TOMBSTONE_LIFETIME_MS + Limits.CLOCK_SKEW_MS) {
                return@transactionWithResult BoardIngestResult.REJECTED
            }

            val rows = q.selectAll().executeAsList()
            if (rows.any { it.kind == KIND_TOMBSTONE && it.post_id.contentEquals(postId) && it.author_key.contentEquals(authorKey) }) {
                return@transactionWithResult BoardIngestResult.REJECTED
            }
            if (rows.any { it.kind == KIND_POST && it.post_id.contentEquals(postId) }) {
                return@transactionWithResult BoardIngestResult.DUPLICATE
            }

            q.insert(
                kind = KIND_POST, post_id = postId, author_key = authorKey, geohash = geohash,
                content = content, nickname = nickname, created_at = createdAt, expires_at = expiresAt,
                flags = flags, signature = signature, deleted_at = 0, retain_until = 0, is_orphan = 0,
            )

            val posts = q.selectAll().executeAsList().filter { it.kind == KIND_POST }
            val authorPosts = posts.filter { it.author_key.contentEquals(authorKey) }
            evictOldest(authorPosts, keep = Limits.MAX_POSTS_PER_AUTHOR, q)
            evictOldest(q.selectAll().executeAsList().filter { it.kind == KIND_POST }, keep = Limits.MAX_POSTS, q)
            BoardIngestResult.ACCEPTED
        }
    }

    /**
     * Ingest a tombstone. Dedups by post id. If the post is known locally, requires the author key to
     * match (only the author can delete), removes the post, and retains the tombstone until the post's
     * original expiry. Otherwise it is an orphan (post unseen/raced ahead): retained until
     * `min(deletedAt + 7d, now + 7d + skew)` and bounded by the orphan caps (5/author, 100 global).
     */
    suspend fun ingestTombstone(
        postId: ByteArray,
        authorKey: ByteArray,
        deletedAt: Long,
        signature: ByteArray,
        nowMs: Long,
    ): BoardIngestResult = withContext(dispatchers.io) {
        val q = databaseManager.getDb().boardQueries
        q.transactionWithResult {
            q.deleteExpiredPosts(nowMs)
            q.deleteExpiredTombstones(nowMs)

            val rows = q.selectAll().executeAsList()
            if (rows.any { it.kind == KIND_TOMBSTONE && it.post_id.contentEquals(postId) }) {
                return@transactionWithResult BoardIngestResult.DUPLICATE
            }

            val maxRetain = minOf(
                deletedAt + Limits.ORPHAN_TOMBSTONE_LIFETIME_MS,
                nowMs + Limits.ORPHAN_TOMBSTONE_LIFETIME_MS + Limits.CLOCK_SKEW_MS,
            )
            val matchingPost = rows.firstOrNull { it.kind == KIND_POST && it.post_id.contentEquals(postId) }
            val retainUntil: Long
            val isOrphan: Boolean
            if (matchingPost != null) {
                if (!matchingPost.author_key.contentEquals(authorKey)) {
                    return@transactionWithResult BoardIngestResult.REJECTED // only the author may delete
                }
                retainUntil = matchingPost.expires_at
                isOrphan = false
                q.deleteById(matchingPost.id)
            } else {
                retainUntil = maxRetain
                isOrphan = true
            }
            if (retainUntil <= nowMs) return@transactionWithResult BoardIngestResult.REJECTED

            q.insert(
                kind = KIND_TOMBSTONE, post_id = postId, author_key = authorKey, geohash = "",
                content = "", nickname = "", created_at = 0, expires_at = 0, flags = 0,
                signature = signature, deleted_at = deletedAt, retain_until = retainUntil,
                is_orphan = if (isOrphan) 1 else 0,
            )
            if (isOrphan) enforceOrphanCaps(authorKey, q)
            BoardIngestResult.ACCEPTED
        }
    }

    /** Live posts for one board (geohash, or "" for the mesh-local board). */
    suspend fun livePostsForGeohash(geohash: String, nowMs: Long): List<Board_entry> = withContext(dispatchers.io) {
        databaseManager.getDb().boardQueries.livePostsByGeohash(geohash, nowMs).executeAsList()
    }

    /** All live posts + live tombstones (for a future gossip re-seed). */
    suspend fun syncCandidates(nowMs: Long): List<Board_entry> = withContext(dispatchers.io) {
        val q = databaseManager.getDb().boardQueries
        q.transactionWithResult {
            q.deleteExpiredPosts(nowMs)
            q.deleteExpiredTombstones(nowMs)
            q.livePosts(nowMs).executeAsList() + q.liveTombstones(nowMs).executeAsList()
        }
    }

    suspend fun wipe() = withContext(dispatchers.io) {
        databaseManager.getDb().boardQueries.deleteAll()
    }

    // Evict oldest-`createdAt` posts from [candidates] beyond [keep] (query object bound to the txn).
    private fun evictOldest(candidates: List<Board_entry>, keep: Int, q: com.app.database.BoardQueries) {
        if (candidates.size <= keep) return
        candidates.sortedBy { it.created_at }.take(candidates.size - keep).forEach { q.deleteById(it.id) }
    }

    // Orphan tombstones reference unseen posts, so bound them per author + globally, oldest received first.
    private fun enforceOrphanCaps(authorKey: ByteArray, q: com.app.database.BoardQueries) {
        val orphans = q.selectAll().executeAsList().filter { it.kind == KIND_TOMBSTONE && it.is_orphan == 1L }
        val authorOrphans = orphans.filter { it.author_key.contentEquals(authorKey) }
        if (authorOrphans.size > Limits.MAX_ORPHAN_TOMBSTONES_PER_AUTHOR) {
            authorOrphans.take(authorOrphans.size - Limits.MAX_ORPHAN_TOMBSTONES_PER_AUTHOR).forEach { q.deleteById(it.id) }
        }
        val remaining = q.selectAll().executeAsList().filter { it.kind == KIND_TOMBSTONE && it.is_orphan == 1L }
        if (remaining.size > Limits.MAX_ORPHAN_TOMBSTONES) {
            remaining.take(remaining.size - Limits.MAX_ORPHAN_TOMBSTONES).forEach { q.deleteById(it.id) }
        }
    }
}
