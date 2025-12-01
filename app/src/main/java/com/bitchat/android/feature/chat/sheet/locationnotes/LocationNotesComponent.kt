package com.bitchat.android.feature.chat.sheet.locationnotes

import com.arkivanov.decompose.value.Value
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.nostr.LocationNotesManager

interface LocationNotesComponent {
    val model: Value<Model>
    
    fun onSetGeohash(geohash: String)
    fun onSendNote(content: String, nickname: String?)
    fun onRefresh()
    fun onClearError()
    fun onCancel()
    fun onRequestLocationPermission()
    fun onEnableLocationServices()
    fun onRefreshLocationChannels()
    
    data class Model(
        val notes: List<LocationNotesManager.Note>,
        val geohash: String?,
        val state: LocationNotesManager.State,
        val errorMessage: String?,
        val initialLoadComplete: Boolean,
        val locationNames: Map<GeohashChannelLevel, String>,
        val availableChannels: List<com.bitchat.android.geohash.GeohashChannel>,
        val nickname: String
    )
}
