package com.bitchat.android.feature.chat.locationchannels

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.bitchat.android.core.common.asValue
import com.bitchat.android.feature.chat.locationchannels.integration.stateToModel
import com.bitchat.android.feature.chat.locationchannels.store.LocationChannelsStore
import com.bitchat.android.feature.chat.locationchannels.store.LocationChannelsStoreFactory
import com.bitchat.android.geohash.ChannelID
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DefaultLocationChannelsComponent(
    componentContext: ComponentContext,
    private val onDismissCallback: () -> Unit
) : LocationChannelsComponent, ComponentContext by componentContext, KoinComponent {

    private val storeFactory: StoreFactory by inject()

    private val store = instanceKeeper.getStore {
        LocationChannelsStoreFactory(storeFactory).create()
    }

    override val model: Value<LocationChannelsComponent.Model> = store.asValue().map(stateToModel)

    override fun onSelectChannel(channelId: ChannelID) {
        store.accept(LocationChannelsStore.Intent.SelectChannel(channelId))
    }

    override fun onToggleBookmark(geohash: String) {
        store.accept(LocationChannelsStore.Intent.ToggleBookmark(geohash))
    }

    override fun onRequestLocationPermission() {
        store.accept(LocationChannelsStore.Intent.RequestLocationPermission)
    }

    override fun onEnableLocationServices() {
        store.accept(LocationChannelsStore.Intent.EnableLocationServices)
    }

    override fun onDisableLocationServices() {
        store.accept(LocationChannelsStore.Intent.DisableLocationServices)
    }

    override fun onRefreshChannels() {
        store.accept(LocationChannelsStore.Intent.RefreshChannels)
    }

    override fun onBeginLiveRefresh() {
        store.accept(LocationChannelsStore.Intent.BeginLiveRefresh)
    }

    override fun onEndLiveRefresh() {
        store.accept(LocationChannelsStore.Intent.EndLiveRefresh)
    }

    override fun onBeginGeohashSampling(geohashes: List<String>) {
        store.accept(LocationChannelsStore.Intent.BeginGeohashSampling(geohashes))
    }

    override fun onEndGeohashSampling() {
        store.accept(LocationChannelsStore.Intent.EndGeohashSampling)
    }

    override fun onDismiss() {
        onDismissCallback()
    }
}
