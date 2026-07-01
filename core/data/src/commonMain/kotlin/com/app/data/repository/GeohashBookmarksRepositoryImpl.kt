package com.app.data.repository

import com.app.database.dao.GeohashDao
import com.app.domain.repository.GeohashBookmarksRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Geohash bookmarks in the encrypted DB ([GeohashDao]). New bookmarks take a strictly-increasing
 * position so the DAO's `ORDER BY position DESC` yields newest-first deterministically.
 */
@SingleIn(AppScope::class)
@Inject
internal class GeohashBookmarksRepositoryImpl(
    private val geohashDao: GeohashDao,
) : GeohashBookmarksRepository {

    override fun observeBookmarks(): Flow<List<String>> = geohashDao.observeBookmarks()

    override fun observeIsBookmarked(geohash: String): Flow<Boolean> {
        val target = normalize(geohash)
        return observeBookmarks().map { target.isNotEmpty() && target in it }
    }

    override suspend fun toggle(geohash: String) {
        val target = normalize(geohash)
        if (target.isEmpty()) return
        if (target in geohashDao.bookmarks()) {
            geohashDao.deleteBookmark(target)
        } else {
            geohashDao.upsertBookmark(target, geohashDao.nextBookmarkPosition())
        }
    }

    private companion object {
        val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz".toSet()

        fun normalize(raw: String): String = raw.trim().lowercase().filter { it in BASE32 }
    }
}
