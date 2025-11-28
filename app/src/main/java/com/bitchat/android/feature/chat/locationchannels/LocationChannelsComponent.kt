package com.bitchat.android.feature.chat.locationchannels

import com.arkivanov.decompose.value.Value
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel

interface LocationChannelsComponent {
    val model: Value<Model>
    
    fun onSelectChannel(channelId: ChannelID)
    fun onToggleBookmark(geohash: String)
    fun onRequestLocationPermission()
    fun onEnableLocationServices()
    fun onDisableLocationServices()
    fun onRefreshChannels()
    fun onBeginLiveRefresh()
    fun onEndLiveRefresh()
    fun onBeginGeohashSampling(geohashes: List<String>)
    fun onEndGeohashSampling()
    fun onDismiss()
    
    data class Model(
        val selectedChannel: ChannelID?,
        val bookmarkedGeohashes: List<String>,
        val availableChannels: List<GeohashChannel>,
        val hasLocationPermission: Boolean,
        val permissionState: com.bitchat.android.geohash.LocationChannelManager.PermissionState?,
        val isTeleported: Boolean,
        val locationNames: Map<GeohashChannelLevel, String>,
        val locationServicesEnabled: Boolean,
        val geohashParticipantCounts: Map<String, Int>,
        val connectedPeers: List<String>,
        val myPeerID: String,
        val bookmarkNames: Map<String, String>
    )
}
