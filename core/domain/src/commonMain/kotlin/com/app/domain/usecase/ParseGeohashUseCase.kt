package com.app.domain.usecase

import com.app.domain.model.GeohashChannel
import com.app.domain.model.GeohashLevel

/**
 * Parse a raw search query into a [GeohashChannel] for the unified-search Geo tab ("teleport").
 * Accepts a bare geohash (optionally `#`-prefixed); place-name geocoding is platform infrastructure
 * and handled elsewhere. Returns null when the query is not a valid geohash.
 *
 * The precision [level][GeohashLevel] is derived from the geohash length: the finest level whose
 * precision does not exceed the length, floored at [GeohashLevel.REGION].
 */
class ParseGeohashUseCase {

    operator fun invoke(query: String): GeohashChannel? {
        val raw = query.trim().removePrefix("#").lowercase()
        if (raw.length !in MIN_LENGTH..MAX_LENGTH) return null
        if (raw.any { it !in BASE32_CHARS }) return null
        return GeohashChannel(level = levelFor(raw.length), geohash = raw)
    }

    private fun levelFor(length: Int): GeohashLevel =
        GeohashLevel.entries
            .filter { it.precision <= length }
            .maxByOrNull { it.precision }
            ?: GeohashLevel.REGION

    private companion object {
        // Base32 geohash alphabet (no a, i, l, o).
        const val BASE32_CHARS = "0123456789bcdefghjkmnpqrstuvwxyz"
        const val MIN_LENGTH = 2
        const val MAX_LENGTH = 12
    }
}
