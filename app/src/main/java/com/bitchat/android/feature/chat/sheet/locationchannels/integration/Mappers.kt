package com.bitchat.android.feature.chat.sheet.locationchannels.integration

import com.bitchat.android.feature.chat.sheet.locationchannels.LocationChannelsComponent
import com.bitchat.android.feature.chat.sheet.locationchannels.store.LocationChannelsStore

internal val stateToModel: (LocationChannelsStore.State) -> LocationChannelsComponent.Model = { state ->
    LocationChannelsComponent.Model(
        selectedChannel = state.selectedChannel,
        bookmarkedGeohashes = state.bookmarkedGeohashes,
        availableChannels = state.availableChannels,
        hasLocationPermission = state.hasLocationPermission,
        permissionState = state.permissionState,
        isTeleported = state.isTeleported,
        locationNames = state.locationNames,
        locationServicesEnabled = state.locationServicesEnabled,
        geohashParticipantCounts = state.geohashParticipantCounts,
        connectedPeers = state.connectedPeers,
        myPeerID = state.myPeerID,
        bookmarkNames = state.bookmarkNames
    )
}
