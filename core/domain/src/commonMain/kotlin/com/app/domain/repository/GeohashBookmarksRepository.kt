package com.app.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * A user-maintained set of bookmarked geohash channels, persisted across sessions. Geohashes are
 * normalised (lowercase base32, no `#`) so toggling is idempotent regardless of how the channel was
 * entered.
 */
interface GeohashBookmarksRepository {

    /** Bookmarked geohashes, newest first. */
    fun observeBookmarks(): Flow<List<String>>

    /** Whether [geohash] is currently bookmarked. */
    fun observeIsBookmarked(geohash: String): Flow<Boolean>

    /** Add the geohash if absent, remove it if present. */
    suspend fun toggle(geohash: String)
}
