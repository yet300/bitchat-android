package com.yet.bitmessage.feature.map.store

import com.arkivanov.mvikotlin.core.store.Store

internal interface MapStore : Store<MapStore.Intent, MapStore.State, Nothing> {

    data class State(
        /** Geohash at the last-tapped point (null until the user taps). */
        val selectedGeohash: String? = null,
    )

    sealed interface Intent {
        /** The user tapped the map at [latitude]/[longitude]; [zoom] drives the geohash precision. */
        data class Tapped(val latitude: Double, val longitude: Double, val zoom: Double) : Intent
    }

    sealed interface Msg {
        data class Selected(val geohash: String) : Msg
    }
}
