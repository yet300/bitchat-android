package com.app.data.repository

import com.app.common.settings.SettingsStore
import com.app.domain.repository.GeohashBookmarksRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Geohash bookmarks over the shared [SettingsStore] (mirrors the other small settings-backed
 * repositories). Stored as a newest-first, comma-separated list of normalised geohashes under a
 * single key — independent of the deleted legacy `GeohashBookmarksStore`.
 */
@SingleIn(AppScope::class)
@Inject
internal class GeohashBookmarksRepositoryImpl(
    private val settings: SettingsStore,
) : GeohashBookmarksRepository {

    override fun observeBookmarks(): Flow<List<String>> =
        settings.getStringFlow(KEY, "").map { it.toList() }

    override fun observeIsBookmarked(geohash: String): Flow<Boolean> {
        val target = normalize(geohash)
        return observeBookmarks().map { target.isNotEmpty() && target in it }
    }

    override suspend fun toggle(geohash: String) {
        val target = normalize(geohash)
        if (target.isEmpty()) return
        val current = settings.getString(KEY, "").toList()
        val next = if (target in current) current - target else listOf(target) + current
        settings.putString(KEY, next.joinToString(SEPARATOR))
    }

    private fun String.toList(): List<String> = split(SEPARATOR).filter { it.isNotBlank() }

    private companion object {
        const val KEY = "geohash_bookmarks"
        const val SEPARATOR = ","
        val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz".toSet()

        fun normalize(raw: String): String = raw.trim().lowercase().filter { it in BASE32 }
    }
}
