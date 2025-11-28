package com.bitchat.android.feature.chat.locationchannels.store

import com.arkivanov.mvikotlin.core.store.Store
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannel

interface LocationChannelsStore : Store<LocationChannelsStore.Intent, LocationChannelsStore.State, LocationChannelsStore.Label> {

    sealed interface Intent {
        data class SelectChannel(val channelId: ChannelID) : Intent
        data class ToggleBookmark(val geohash: String) : Intent
        data object RequestLocationPermission : Intent
        data object EnableLocationServices : Intent
        data object DisableLocationServices : Intent
        data object RefreshChannels : Intent
        data object BeginLiveRefresh : Intent
        data object EndLiveRefresh : Intent
        data class BeginGeohashSampling(val geohashes: List<String>) : Intent
        data object EndGeohashSampling : Intent
    }

    data class State(
        val selectedChannel: ChannelID? = null,
        val bookmarkedGeohashes: List<String> = emptyList(),
        val availableChannels: List<GeohashChannel> = emptyList(),
        val hasLocationPermission: Boolean = false,
        val permissionState: com.bitchat.android.geohash.LocationChannelManager.PermissionState? = null,
        val isTeleported: Boolean = false,
        val locationNames: Map<com.bitchat.android.geohash.GeohashChannelLevel, String> = emptyMap(),
        val locationServicesEnabled: Boolean = false,
        val geohashParticipantCounts: Map<String, Int> = emptyMap(),
        val connectedPeers: List<String> = emptyList(),
        val myPeerID: String = "",
        val bookmarkNames: Map<String, String> = emptyMap()
    )

    sealed interface Action {
        data object Init : Action
    }

    sealed interface Msg {
        data class SelectedChannelChanged(val channelId: ChannelID?) : Msg
        data class BookmarksChanged(val bookmarks: List<String>) : Msg
        data class AvailableChannelsChanged(val channels: List<GeohashChannel>) : Msg
        data class LocationPermissionChanged(val hasPermission: Boolean) : Msg
        data class PermissionStateChanged(val state: com.bitchat.android.geohash.LocationChannelManager.PermissionState?) : Msg
        data class TeleportedChanged(val isTeleported: Boolean) : Msg
        data class LocationNamesChanged(val names: Map<com.bitchat.android.geohash.GeohashChannelLevel, String>) : Msg
        data class LocationServicesEnabledChanged(val enabled: Boolean) : Msg
        data class GeohashParticipantCountsChanged(val counts: Map<String, Int>) : Msg
        data class ConnectedPeersChanged(val peers: List<String>) : Msg
        data class MyPeerIDChanged(val peerID: String) : Msg
        data class BookmarkNamesChanged(val names: Map<String, String>) : Msg
    }

    sealed interface Label {
        data object RequestLocationPermission : Label
    }
}
