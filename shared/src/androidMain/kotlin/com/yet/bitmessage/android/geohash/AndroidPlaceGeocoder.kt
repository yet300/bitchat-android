package com.yet.bitmessage.android.geohash

import android.content.Context
import android.location.Geocoder
import com.app.common.AppDispatchers
import com.app.common.geohash.Geohash
import com.app.domain.model.GeohashChannel
import com.app.domain.model.GeohashLevel
import com.app.domain.repository.PlaceGeocoder
import kotlinx.coroutines.withContext

/**
 * Forward geocoding via the platform [Geocoder] (place name → coordinates), encoded to a
 * neighbourhood-precision geohash for the search Geo tab. Runs off the main thread; returns null
 * when geocoding is unavailable or the name doesn't resolve. (OpenStreetMap forward fallback —
 * mirroring [GeocoderFactory]'s reverse path — is a follow-up.)
 */
class AndroidPlaceGeocoder(
    private val context: Context,
    private val dispatchers: AppDispatchers,
) : PlaceGeocoder {

    @Suppress("DEPRECATION") // Synchronous getFromLocationName is fine off the main thread.
    override suspend fun toGeohash(query: String): GeohashChannel? = withContext(dispatchers.io) {
        if (query.isBlank() || !Geocoder.isPresent()) return@withContext null
        val match = runCatching { Geocoder(context).getFromLocationName(query, 1) }
            .getOrNull()
            ?.firstOrNull()
            ?: return@withContext null
        val precision = GeohashLevel.NEIGHBORHOOD.precision
        GeohashChannel(
            level = GeohashLevel.NEIGHBORHOOD,
            geohash = Geohash.encode(match.latitude, match.longitude, precision),
        )
    }
}
