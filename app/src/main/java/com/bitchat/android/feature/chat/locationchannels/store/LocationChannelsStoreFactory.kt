package com.bitchat.android.feature.chat.locationchannels.store

import androidx.lifecycle.asFlow
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.bitchat.android.geohash.GeohashBookmarksStore
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.ui.ChatViewModel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal class LocationChannelsStoreFactory(
    private val storeFactory: StoreFactory
) : KoinComponent {

    private val locationChannelManager: LocationChannelManager by inject()
    private val bookmarksStore: GeohashBookmarksStore by inject()
    private val chatViewModel: ChatViewModel by inject()
    private val meshService: BluetoothMeshService by inject()

    fun create(): LocationChannelsStore =
        object : LocationChannelsStore,
            Store<LocationChannelsStore.Intent, LocationChannelsStore.State, LocationChannelsStore.Label> by storeFactory.create(
                name = "LocationChannelsStore",
                initialState = LocationChannelsStore.State(
                    selectedChannel = locationChannelManager.selectedChannel.value,
                    bookmarkedGeohashes = bookmarksStore.getBookmarks(),
                    isTeleported = locationChannelManager.teleported.value,
                    availableChannels = locationChannelManager.availableChannels.value,
                    hasLocationPermission = locationChannelManager.permissionState.value == LocationChannelManager.PermissionState.AUTHORIZED,
                    permissionState = locationChannelManager.permissionState.value,
                    locationNames = locationChannelManager.locationNames.value,
                    locationServicesEnabled = locationChannelManager.locationServicesEnabled.value,
                    geohashParticipantCounts = chatViewModel.geohashParticipantCounts.value ?: emptyMap(),
                    connectedPeers = chatViewModel.connectedPeers.value ?: emptyList(),
                    myPeerID = meshService.myPeerID,
                    bookmarkNames = bookmarksStore.bookmarkNames.value
                ),
                bootstrapper = SimpleBootstrapper(LocationChannelsStore.Action.Init),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl
            ) {}

    private inner class ExecutorImpl :
        CoroutineExecutor<LocationChannelsStore.Intent, LocationChannelsStore.Action, LocationChannelsStore.State, LocationChannelsStore.Msg, LocationChannelsStore.Label>() {

        override fun executeAction(action: LocationChannelsStore.Action) {
            when (action) {
                LocationChannelsStore.Action.Init -> {
                    // Subscribe to location channel changes
                    scope.launch {
                        locationChannelManager.selectedChannel.collect { channel ->
                            dispatch(LocationChannelsStore.Msg.SelectedChannelChanged(channel))
                        }
                    }
                    
                    // Subscribe to teleport status
                    scope.launch {
                        locationChannelManager.teleported.collect { teleported ->
                            dispatch(LocationChannelsStore.Msg.TeleportedChanged(teleported))
                        }
                    }
                    
                    // Subscribe to bookmarks changes
                    scope.launch {
                        bookmarksStore.bookmarks.collect { bookmarks ->
                            dispatch(LocationChannelsStore.Msg.BookmarksChanged(bookmarks))
                        }
                    }

                    // Subscribe to available channels
                    scope.launch {
                        locationChannelManager.availableChannels.collect { channels ->
                            dispatch(LocationChannelsStore.Msg.AvailableChannelsChanged(channels))
                        }
                    }

                    // Subscribe to permission state
                    scope.launch {
                        locationChannelManager.permissionState.collect { state ->
                            dispatch(LocationChannelsStore.Msg.PermissionStateChanged(state))
                            dispatch(LocationChannelsStore.Msg.LocationPermissionChanged(state == LocationChannelManager.PermissionState.AUTHORIZED))
                        }
                    }

                    // Subscribe to bookmark names
                    scope.launch {
                        bookmarksStore.bookmarkNames.collect { names ->
                            dispatch(LocationChannelsStore.Msg.BookmarkNamesChanged(names))
                        }
                    }

                    // Subscribe to location names
                    scope.launch {
                        locationChannelManager.locationNames.collect { names ->
                            dispatch(LocationChannelsStore.Msg.LocationNamesChanged(names))
                        }
                    }

                    // Subscribe to location services enabled
                    scope.launch {
                        locationChannelManager.locationServicesEnabled.collect { enabled ->
                            dispatch(LocationChannelsStore.Msg.LocationServicesEnabledChanged(enabled))
                        }
                    }

                    // Subscribe to geohash participant counts (from ChatViewModel LiveData)
                    scope.launch {
                        chatViewModel.geohashParticipantCounts.asFlow().collect { counts ->
                            dispatch(LocationChannelsStore.Msg.GeohashParticipantCountsChanged(counts ?: emptyMap()))
                        }
                    }

                    // Subscribe to connected peers (from ChatViewModel LiveData)
                    scope.launch {
                        chatViewModel.connectedPeers.asFlow().collect { peers ->
                            dispatch(LocationChannelsStore.Msg.ConnectedPeersChanged(peers ?: emptyList()))
                        }
                    }

                    // myPeerID doesn't change, but if it did we'd subscribe here
                    // For now, just dispatch the initial value
                    dispatch(LocationChannelsStore.Msg.MyPeerIDChanged(meshService.myPeerID))
                }
            }
        }

        override fun executeIntent(intent: LocationChannelsStore.Intent) {
            when (intent) {
                is LocationChannelsStore.Intent.SelectChannel -> {
                    locationChannelManager.select(intent.channelId)
                }
                is LocationChannelsStore.Intent.ToggleBookmark -> {
                    val currentBookmarks = state().bookmarkedGeohashes
                    if (currentBookmarks.contains(intent.geohash)) {
                        bookmarksStore.remove(intent.geohash)
                    } else {
                        bookmarksStore.add(intent.geohash)
                    }
                }
                LocationChannelsStore.Intent.RequestLocationPermission -> {
                    locationChannelManager.enableLocationChannels()
                }
                LocationChannelsStore.Intent.EnableLocationServices -> {
                    locationChannelManager.enableLocationServices()
                }
                LocationChannelsStore.Intent.DisableLocationServices -> {
                    locationChannelManager.disableLocationServices()
                }
                LocationChannelsStore.Intent.RefreshChannels -> {
                    locationChannelManager.refreshChannels()
                }
                LocationChannelsStore.Intent.BeginLiveRefresh -> {
                    locationChannelManager.beginLiveRefresh()
                }
                LocationChannelsStore.Intent.EndLiveRefresh -> {
                    locationChannelManager.endLiveRefresh()
                }
                is LocationChannelsStore.Intent.BeginGeohashSampling -> {
                    chatViewModel.beginGeohashSampling(intent.geohashes)
                }
                LocationChannelsStore.Intent.EndGeohashSampling -> {
                    chatViewModel.endGeohashSampling()
                }
            }
        }
    }

    private object ReducerImpl : Reducer<LocationChannelsStore.State, LocationChannelsStore.Msg> {
        override fun LocationChannelsStore.State.reduce(msg: LocationChannelsStore.Msg): LocationChannelsStore.State =
            when (msg) {
                is LocationChannelsStore.Msg.SelectedChannelChanged -> copy(selectedChannel = msg.channelId)
                is LocationChannelsStore.Msg.BookmarksChanged -> copy(bookmarkedGeohashes = msg.bookmarks)
                is LocationChannelsStore.Msg.AvailableChannelsChanged -> copy(availableChannels = msg.channels)
                is LocationChannelsStore.Msg.LocationPermissionChanged -> copy(hasLocationPermission = msg.hasPermission)
                is LocationChannelsStore.Msg.PermissionStateChanged -> copy(permissionState = msg.state)
                is LocationChannelsStore.Msg.TeleportedChanged -> copy(isTeleported = msg.isTeleported)
                is LocationChannelsStore.Msg.LocationNamesChanged -> copy(locationNames = msg.names)
                is LocationChannelsStore.Msg.LocationServicesEnabledChanged -> copy(locationServicesEnabled = msg.enabled)
                is LocationChannelsStore.Msg.GeohashParticipantCountsChanged -> copy(geohashParticipantCounts = msg.counts)
                is LocationChannelsStore.Msg.ConnectedPeersChanged -> copy(connectedPeers = msg.peers)
                is LocationChannelsStore.Msg.MyPeerIDChanged -> copy(myPeerID = msg.peerID)
                is LocationChannelsStore.Msg.BookmarkNamesChanged -> copy(bookmarkNames = msg.names)
            }
    }
}
