package com.yet.bitmessage.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.app.common.geohash.Geohash
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.Feature as DslFeature
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

/**
 * Interactive MapLibre map (no API key — default demotiles style). Centres on [initialGeohash] and
 * reports each tapped point's geographic coordinate + current zoom via [onTap] — the caller derives
 * the geohash. Native replacement for the deleted Leaflet WebView picker (P6).
 *
 * When [selectedGeohash] is non-null the map draws a fill/line grid for the selected cell and its
 * 8 neighbours with a label overlay showing the geohash string for each cell.
 */
@Composable
fun GeohashMapPicker(
    initialGeohash: String?,
    selectedGeohash: String?,
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

    val gridFeatures = remember(selectedGeohash) {
        selectedGeohash?.let { buildGeohashGrid(it) }
    }

    MaplibreMap(
        cameraState = camera,
        onMapClick = { position, _ ->
            onTap(position.latitude, position.longitude, camera.position.zoom)
            ClickResult.Pass
        },
        modifier = modifier,
        content = {
            if (gridFeatures != null) {
                val source = rememberGeoJsonSource(
                    data = GeoJsonData.Features(gridFeatures),
                )
                FillLayer(
                    id = "geohash-fill",
                    source = source,
                    color = const(Color(0x331976D2L)),
                    opacity = const(1f),
                )
                LineLayer(
                    id = "geohash-line",
                    source = source,
                    color = const(Color(0xFF1976D2L)),
                    width = const(1.5.dp),
                    opacity = const(0.8f),
                )
                SymbolLayer(
                    id = "geohash-labels",
                    source = source,
                    textField = format(span(DslFeature["label"].asString())),
                    textColor = const(Color(0xFF1976D2L)),
                    textHaloColor = const(Color.White),
                    textHaloWidth = const(1.5.dp),
                    textSize = const(11f.em),
                )
            }
        },
    )
}

private const val DEFAULT_PRECISION = 5

private fun zoomForPrecision(precision: Int): Double = when (precision) {
    1 -> 1.0; 2 -> 2.0; 3 -> 3.0; 4 -> 4.0; 5 -> 5.0
    6 -> 7.0; 7 -> 9.0; 8 -> 11.0; 9 -> 13.0; 10 -> 15.0; 11 -> 17.0
    else -> 18.0
}

private fun buildGeohashGrid(center: String): FeatureCollection<Polygon, JsonObject?> {
    val cells = listOf(center) + Geohash.neighborsSamePrecision(center).toList()
    val features = cells.map { gh ->
        val b = Geohash.decodeToBounds(gh)
        Feature(
            geometry = Polygon(
                listOf(
                    listOf(
                        Position(longitude = b.lonMin, latitude = b.latMin),
                        Position(longitude = b.lonMax, latitude = b.latMin),
                        Position(longitude = b.lonMax, latitude = b.latMax),
                        Position(longitude = b.lonMin, latitude = b.latMax),
                        Position(longitude = b.lonMin, latitude = b.latMin),
                    )
                )
            ),
            properties = buildJsonObject { put("label", gh) },
        )
    }
    return FeatureCollection(features)
}
