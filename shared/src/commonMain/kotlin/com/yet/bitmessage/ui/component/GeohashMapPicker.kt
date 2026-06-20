package com.yet.bitmessage.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.app.common.geohash.Geohash
import org.maplibre.spatialk.geojson.Position
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MaplibreMap

/**
 * Interactive geohash picker over a MapLibre map (no API key — default demotiles style). Centres on
 * [initialGeohash], and reports the geohash under the map centre as the user pans/zooms — precision
 * derived from zoom, encoding via the shared [Geohash] codec (the native replacement for the old
 * Leaflet WebView picker, P6).
 */
@Composable
fun GeohashMapPicker(
    initialGeohash: String?,
    onGeohashChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (initLat, initLon) = remember(initialGeohash) {
        initialGeohash?.let { runCatching { Geohash.decodeToCenter(it) }.getOrNull() } ?: (0.0 to 0.0)
    }
    val initialPrecision = initialGeohash?.length ?: DEFAULT_PRECISION
    val camera = rememberCameraState(
        firstPosition = CameraPosition(
            target = Position(latitude = initLat, longitude = initLon),
            zoom = zoomForPrecision(initialPrecision),
        ),
    )

    val target = camera.position.target
    val zoom = camera.position.zoom
    LaunchedEffect(target.latitude, target.longitude, zoom) {
        val precision = precisionForZoom(zoom)
        onGeohashChanged(Geohash.encode(target.latitude, target.longitude, precision))
    }

    MaplibreMap(cameraState = camera, modifier = modifier)
}

private const val DEFAULT_PRECISION = 5

/** Zoom -> geohash precision, inverse of [zoomForPrecision] (ported from the legacy picker). */
private fun precisionForZoom(zoom: Double): Int {
    var precision = 1
    for (p in 1..12) if (zoomForPrecision(p) <= zoom) precision = p
    return precision
}

/** Geohash precision -> map zoom (same table the legacy Leaflet picker used). */
private fun zoomForPrecision(precision: Int): Double = when (precision) {
    1 -> 1.0; 2 -> 2.0; 3 -> 3.0; 4 -> 4.0; 5 -> 5.0
    6 -> 7.0; 7 -> 9.0; 8 -> 11.0; 9 -> 13.0; 10 -> 15.0; 11 -> 17.0
    else -> 18.0
}
