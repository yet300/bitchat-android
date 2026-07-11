package com.app.domain.repository

import com.app.domain.model.BoardPost
import kotlinx.coroutines.flow.Flow

/**
 * Geohash bulletin boards: signed public notices scoped to a geohash (or the mesh-local board),
 * designed to outlive chat — a post stays until its author-chosen expiry (max 7 days). Anyone in the
 * geohash may post; the only deletion is an author-signed tombstone. All anti-spam (per-author +
 * global caps, orphan-tombstone caps, retention) is enforced in the store — see
 * docs/GROUPS_BOARDS_RESEARCH.md §3.3 for the complete list + the Sybil residual risk.
 *
 * Post IDs cross this API as lowercase hex. Headless business surface; a future UI consumes it.
 */
interface BoardRepository {

    /** Posts newly accepted from the wire or local echo (not disk restores) — drives new-pin alerts. */
    val postArrivals: Flow<BoardPost>

    /** Live posts for one board (geohash, or "" for the mesh-local board), urgent first then newest. */
    suspend fun posts(geohash: String): List<BoardPost>

    /**
     * Signs and broadcasts a post. [expiryDays] is clamped to 1..7. Returns false on empty/oversize
     * content or a signing failure.
     */
    suspend fun createPost(content: String, geohash: String, urgent: Boolean, expiryDays: Int): Boolean

    /** Signs and broadcasts a tombstone for one of our own posts (by hex ID). */
    suspend fun deletePost(postIdHex: String): Boolean
}
