package com.bitchat.android.feature.chat.sheet.locationnotes

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.bitchat.android.core.common.asValue
import com.bitchat.android.feature.chat.locationnotes.integration.stateToModel
import com.bitchat.android.feature.chat.locationnotes.store.LocationNotesStore
import com.bitchat.android.feature.chat.locationnotes.store.LocationNotesStoreFactory
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DefaultLocationNotesComponent(
    componentContext: ComponentContext
) : LocationNotesComponent, ComponentContext by componentContext, KoinComponent {

    private val storeFactory: StoreFactory by inject()

    private val store = instanceKeeper.getStore {
        LocationNotesStoreFactory(storeFactory).create()
    }

    override val model: Value<LocationNotesComponent.Model> = store.asValue().map(stateToModel)

    override fun onSetGeohash(geohash: String) {
        store.accept(LocationNotesStore.Intent.SetGeohash(geohash))
    }

    override fun onSendNote(content: String, nickname: String?) {
        store.accept(LocationNotesStore.Intent.SendNote(content, nickname))
    }

    override fun onRefresh() {
        store.accept(LocationNotesStore.Intent.Refresh)
    }

    override fun onClearError() {
        store.accept(LocationNotesStore.Intent.ClearError)
    }

    override fun onCancel() {
        store.accept(LocationNotesStore.Intent.Cancel)
    }

    override fun onRequestLocationPermission() {
        store.accept(LocationNotesStore.Intent.RequestLocationPermission)
    }

    override fun onEnableLocationServices() {
        store.accept(LocationNotesStore.Intent.EnableLocationServices)
    }

    override fun onRefreshLocationChannels() {
        store.accept(LocationNotesStore.Intent.RefreshLocationChannels)
    }
}
