package com.yet.bitmessage.feature.map

import com.app.common.decompose.asValue
import com.app.domain.model.ConversationId
import com.app.domain.usecase.ParseGeohashUseCase
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yet.bitmessage.feature.map.store.MapStore
import com.yet.bitmessage.feature.map.store.MapStoreFactory
import dev.zacsweers.metro.Inject

internal class DefaultMapComponent(
    componentContext: ComponentContext,
    storeFactory: MapStoreFactory,
    private val initialGeohash: String?,
    private val onConfirm: (id: ConversationId) -> Unit,
    private val onClose: () -> Unit,
) : MapComponent, ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }
    private val parseGeohash = ParseGeohashUseCase()

    override val model: Value<MapComponent.Model> = store.asValue().map { state ->
        MapComponent.Model(initialGeohash = initialGeohash, selectedGeohash = state.selectedGeohash)
    }

    override fun onMapTapped(latitude: Double, longitude: Double, zoom: Double) =
        store.accept(MapStore.Intent.Tapped(latitude, longitude, zoom))

    override fun onConfirmClicked() {
        val geohash = store.state.selectedGeohash ?: return
        parseGeohash(geohash)?.let { onConfirm(ConversationId.Geohash(it)) }
    }

    override fun onCloseClicked() = onClose()
}

@Inject
internal class DefaultMapComponentFactory(
    private val storeFactory: StoreFactory,
) : MapComponent.Factory {
    override fun create(
        componentContext: ComponentContext,
        initialGeohash: String?,
        onConfirm: (id: ConversationId) -> Unit,
        onClose: () -> Unit,
    ): MapComponent = DefaultMapComponent(
        componentContext = componentContext,
        storeFactory = MapStoreFactory(storeFactory),
        initialGeohash = initialGeohash,
        onConfirm = onConfirm,
        onClose = onClose,
    )
}
