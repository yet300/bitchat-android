package com.app.data.geohash

import com.app.common.geohash.Geohash
import com.app.domain.model.GeohashChannel
import com.app.domain.model.GeohashLevel
import com.app.domain.repository.PlaceGeocoder
import dev.jordond.compass.geocoder.Geocoder
import dev.jordond.compass.geocoder.MobileGeocoder
import dev.jordond.compass.geocoder.mobile.placesOrNull

/**
 * Forward geocoding (place name → coordinates) via Compass' mobile [Geocoder] (platform Geocoder on
 * Android, CLGeocoder on iOS), encoded to a neighbourhood-precision geohash for the search Geo tab.
 *
 * Platform-free (commonMain) — replaces the former androidMain `AndroidPlaceGeocoder`. Returns null
 * when the query is blank or does not resolve; Compass runs the lookup off the main thread itself.
 */
class CompassPlaceGeocoder(
    private val geocoder: Geocoder = MobileGeocoder(),
) : PlaceGeocoder {

    override suspend fun toGeohash(query: String): GeohashChannel? {
        if (query.isBlank()) return null
        val place = runCatching { geocoder.placesOrNull(query) }.getOrNull()?.firstOrNull()
            ?: return null
        val precision = GeohashLevel.NEIGHBORHOOD.precision
        return GeohashChannel(
            level = GeohashLevel.NEIGHBORHOOD,
            geohash = Geohash.encode(place.coordinates.latitude, place.coordinates.longitude, precision),
        )
    }
}
