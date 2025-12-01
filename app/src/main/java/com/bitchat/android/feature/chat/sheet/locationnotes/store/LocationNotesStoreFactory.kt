package com.bitchat.android.feature.chat.sheet.locationnotes.store

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.nostr.LocationNotesManager
import com.bitchat.android.ui.DataManager
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal class LocationNotesStoreFactory(
    private val storeFactory: StoreFactory
) : KoinComponent {

    private val locationNotesManager: LocationNotesManager by inject()
    private val locationChannelManager: LocationChannelManager by inject()
    private val dataManager: DataManager by inject()

    fun create(): LocationNotesStore =
        object : LocationNotesStore,
            Store<LocationNotesStore.Intent, LocationNotesStore.State, LocationNotesStore.Label> by storeFactory.create(
                name = "LocationNotesStore",
                initialState = LocationNotesStore.State(
                    notes = locationNotesManager.notes.value,
                    geohash = locationNotesManager.geohash.value,
                    state = locationNotesManager.state.value,
                    errorMessage = locationNotesManager.errorMessage.value,
                    initialLoadComplete = locationNotesManager.initialLoadComplete.value,
                    locationNames = locationChannelManager.locationNames.value,
                    availableChannels = locationChannelManager.availableChannels.value,
                    nickname = dataManager.loadNickname()
                ),
                bootstrapper = SimpleBootstrapper(LocationNotesStore.Action.Init),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl
            ) {}

    private inner class ExecutorImpl :
        CoroutineExecutor<LocationNotesStore.Intent, LocationNotesStore.Action, LocationNotesStore.State, LocationNotesStore.Msg, LocationNotesStore.Label>() {

        override fun executeAction(action: LocationNotesStore.Action) {
            when (action) {
                LocationNotesStore.Action.Init -> {
                    // Subscribe to notes changes
                    scope.launch {
                        locationNotesManager.notes.collect { notes ->
                            dispatch(LocationNotesStore.Msg.NotesChanged(notes))
                        }
                    }
                    
                    // Subscribe to geohash changes
                    scope.launch {
                        locationNotesManager.geohash.collect { geohash ->
                            dispatch(LocationNotesStore.Msg.GeohashChanged(geohash))
                        }
                    }
                    
                    // Subscribe to state changes
                    scope.launch {
                        locationNotesManager.state.collect { state ->
                            dispatch(LocationNotesStore.Msg.StateChanged(state))
                        }
                    }
                    
                    // Subscribe to error message changes
                    scope.launch {
                        locationNotesManager.errorMessage.collect { message ->
                            dispatch(LocationNotesStore.Msg.ErrorMessageChanged(message))
                        }
                    }
                    
                    // Subscribe to initial load complete changes
                    scope.launch {
                        locationNotesManager.initialLoadComplete.collect { complete ->
                            dispatch(LocationNotesStore.Msg.InitialLoadCompleteChanged(complete))
                        }
                    }

                    // Subscribe to location names
                    scope.launch {
                        locationChannelManager.locationNames.collect { names ->
                            dispatch(LocationNotesStore.Msg.LocationNamesChanged(names))
                        }
                    }

                    // Subscribe to available channels
                    scope.launch {
                        locationChannelManager.availableChannels.collect { channels ->
                            dispatch(LocationNotesStore.Msg.AvailableChannelsChanged(channels))
                        }
                    }

                    // Subscribe to nickname changes from DataManager
                    // Note: DataManager doesn't expose a Flow, so we load it once
                    // If nickname needs to be reactive, we'd need to add Flow support to DataManager
                    // For now, we'll just use the initial value
                }
            }
        }

        override fun executeIntent(intent: LocationNotesStore.Intent) {
            when (intent) {
                is LocationNotesStore.Intent.SetGeohash -> {
                    locationNotesManager.setGeohash(intent.geohash)
                }
                is LocationNotesStore.Intent.SendNote -> {
                    locationNotesManager.send(intent.content, intent.nickname)
                }
                LocationNotesStore.Intent.Refresh -> {
                    locationNotesManager.refresh()
                }
                LocationNotesStore.Intent.ClearError -> {
                    locationNotesManager.clearError()
                }
                LocationNotesStore.Intent.Cancel -> {
                    locationNotesManager.cancel()
                }
                LocationNotesStore.Intent.RequestLocationPermission -> {
                    locationChannelManager.enableLocationChannels()
                }
                LocationNotesStore.Intent.EnableLocationServices -> {
                    locationChannelManager.enableLocationServices()
                }
                LocationNotesStore.Intent.RefreshLocationChannels -> {
                    locationChannelManager.refreshChannels()
                }
            }
        }
    }

    private object ReducerImpl : Reducer<LocationNotesStore.State, LocationNotesStore.Msg> {
        override fun LocationNotesStore.State.reduce(msg: LocationNotesStore.Msg): LocationNotesStore.State =
            when (msg) {
                is LocationNotesStore.Msg.NotesChanged -> copy(notes = msg.notes)
                is LocationNotesStore.Msg.GeohashChanged -> copy(geohash = msg.geohash)
                is LocationNotesStore.Msg.StateChanged -> copy(state = msg.state)
                is LocationNotesStore.Msg.ErrorMessageChanged -> copy(errorMessage = msg.message)
                is LocationNotesStore.Msg.InitialLoadCompleteChanged -> copy(initialLoadComplete = msg.complete)
                is LocationNotesStore.Msg.LocationNamesChanged -> copy(locationNames = msg.names)
                is LocationNotesStore.Msg.AvailableChannelsChanged -> copy(availableChannels = msg.channels)
                is LocationNotesStore.Msg.NicknameChanged -> copy(nickname = msg.nickname)
            }
    }
}
