package com.yet.bitmessage.feature.chats.details.notes

import com.app.domain.model.LocationNote
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value

/**
 * Location-notes sheet for a building-level geohash: a geo-anchored feed of posts plus a composer.
 * Distinct from the geohash chat — these are longer-lived notes pinned to a place.
 */
interface LocationNotesComponent {

    val model: Value<Model>

    fun onDraftChanged(text: String)

    fun onSendClicked()

    fun onCloseClicked()

    data class Model(
        val notes: List<LocationNote>,
        val isLoading: Boolean,
        val draft: String,
        val canSend: Boolean,
    )

    fun interface Factory {
        fun create(componentContext: ComponentContext, geohash: String, onClose: () -> Unit): LocationNotesComponent
    }
}
