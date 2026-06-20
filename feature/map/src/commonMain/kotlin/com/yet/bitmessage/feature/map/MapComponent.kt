package com.yet.bitmessage.feature.map

import com.app.domain.model.ConversationId
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value

/**
 * Full-screen map geohash picker (P6): a root-level screen. Pan/zoom a MapLibre map and tap to
 * choose a location; the geohash under the tapped point is selected, and confirming teleports to
 * that geohash channel. Navigation (open the conversation / close) is owned by the root.
 */
interface MapComponent {

    val model: Value<Model>

    /** A map point was tapped (geographic coordinate + current zoom for precision). */
    fun onMapTapped(latitude: Double, longitude: Double, zoom: Double)

    /** Teleport to the currently selected geohash. */
    fun onConfirmClicked()

    fun onCloseClicked()

    data class Model(
        /** Where the map starts centred (null -> world view). */
        val initialGeohash: String?,
        /** Geohash under the last-tapped point, or null until the user taps. */
        val selectedGeohash: String?,
    ) {
        val canConfirm: Boolean get() = selectedGeohash != null
    }

    fun interface Factory {
        fun create(
            componentContext: ComponentContext,
            initialGeohash: String?,
            onConfirm: (id: ConversationId) -> Unit,
            onClose: () -> Unit,
        ): MapComponent
    }
}
