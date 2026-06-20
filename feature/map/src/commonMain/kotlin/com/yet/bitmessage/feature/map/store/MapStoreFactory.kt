package com.yet.bitmessage.feature.map.store

import com.app.common.geohash.Geohash
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor

internal class MapStoreFactory(
    private val storeFactory: StoreFactory,
) {
    fun create(): MapStore =
        object : MapStore,
            Store<MapStore.Intent, MapStore.State, Nothing> by storeFactory.create(
                name = "MapStore",
                initialState = MapStore.State(),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl,
            ) {}

    private object ReducerImpl : Reducer<MapStore.State, MapStore.Msg> {
        override fun MapStore.State.reduce(msg: MapStore.Msg): MapStore.State =
            when (msg) {
                is MapStore.Msg.Selected -> copy(selectedGeohash = msg.geohash)
            }
    }

    private inner class ExecutorImpl :
        CoroutineExecutor<MapStore.Intent, Nothing, MapStore.State, MapStore.Msg, Nothing>() {

        override fun executeIntent(intent: MapStore.Intent) {
            when (intent) {
                is MapStore.Intent.Tapped -> {
                    val geohash = Geohash.encode(intent.latitude, intent.longitude, precisionForZoom(intent.zoom))
                    if (geohash.isNotEmpty()) dispatch(MapStore.Msg.Selected(geohash))
                }
            }
        }
    }

    private companion object {
        /** Zoom -> geohash precision (inverse of the legacy picker's per-precision zoom table). */
        fun precisionForZoom(zoom: Double): Int {
            var precision = 1
            for (p in 1..12) if (zoomForPrecision(p) <= zoom) precision = p
            return precision
        }

        fun zoomForPrecision(precision: Int): Double = when (precision) {
            1 -> 1.0; 2 -> 2.0; 3 -> 3.0; 4 -> 4.0; 5 -> 5.0
            6 -> 7.0; 7 -> 9.0; 8 -> 11.0; 9 -> 13.0; 10 -> 15.0; 11 -> 17.0
            else -> 18.0
        }
    }
}
