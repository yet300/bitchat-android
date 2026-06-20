package com.yet.bitmessage.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.app.common.geohash.Geohash
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position

/**
 * Interactive MapLibre map (no API key — default demotiles style). Centres on [initialGeohash] and
 * reports each tapped point's geographic coordinate + current zoom via [onTap] — the caller derives
 * the geohash. Native replacement for the deleted Leaflet WebView picker (P6).
 */
@Composable
fun GeohashMapPicker(
    initialGeohash: String?,
    onTap: (latitude: Double, longitude: Double, zoom: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (initLat, initLon) = remember(initialGeohash) {
        initialGeohash?.let { runCatching { Geohash.decodeToCenter(it) }.getOrNull() } ?: (0.0 to 0.0)
    }
    val camera = rememberCameraState(
        firstPosition = CameraPosition(
            target = Position(latitude = initLat, longitude = initLon),
            zoom = zoomForPrecision(initialGeohash?.length ?: DEFAULT_PRECISION),
        ),
    )

    MaplibreMap(
        cameraState = camera,
        onMapClick = { position, _ ->
            onTap(position.latitude, position.longitude, camera.position.zoom)
            ClickResult.Pass
        },
        modifier = modifier,
    )
}

private const val DEFAULT_PRECISION = 5

private fun zoomForPrecision(precision: Int): Double = when (precision) {
    1 -> 1.0; 2 -> 2.0; 3 -> 3.0; 4 -> 4.0; 5 -> 5.0
    6 -> 7.0; 7 -> 9.0; 8 -> 11.0; 9 -> 13.0; 10 -> 15.0; 11 -> 17.0
    else -> 18.0
}
