package com.app.domain.repository

import com.app.domain.model.GeohashChannel

/**
 * Forward geocoding for the unified-search Geo tab: resolve a place name to a [GeohashChannel] so
 * the user can teleport to it. The platform implementation does the actual geocoding (and may hit
 * the network); returns null when the name can't be resolved.
 */
interface PlaceGeocoder {
    suspend fun toGeohash(query: String): GeohashChannel?
}
