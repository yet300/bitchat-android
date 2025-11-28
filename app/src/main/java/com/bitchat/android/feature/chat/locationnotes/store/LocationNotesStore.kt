package com.bitchat.android.feature.chat.locationnotes.store

import com.arkivanov.mvikotlin.core.store.Store
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.nostr.LocationNotesManager

interface LocationNotesStore : Store<LocationNotesStore.Intent, LocationNotesStore.State, LocationNotesStore.Label> {

    sealed interface Intent {
        data class SetGeohash(val geohash: String) : Intent
        data class SendNote(val content: String, val nickname: String?) : Intent
        data object Refresh : Intent
        data object ClearError : Intent
        data object Cancel : Intent
        data object RequestLocationPermission : Intent
        data object EnableLocationServices : Intent
        data object RefreshLocationChannels : Intent
    }

    data class State(
        val notes: List<LocationNotesManager.Note> = emptyList(),
        val geohash: String? = null,
        val state: LocationNotesManager.State = LocationNotesManager.State.IDLE,
        val errorMessage: String? = null,
        val initialLoadComplete: Boolean = false,
        val locationNames: Map<GeohashChannelLevel, String> = emptyMap(),
        val availableChannels: List<com.bitchat.android.geohash.GeohashChannel> = emptyList(),
        val nickname: String = ""
    )

    sealed interface Action {
        data object Init : Action
    }

    sealed interface Msg {
        data class NotesChanged(val notes: List<LocationNotesManager.Note>) : Msg
        data class GeohashChanged(val geohash: String?) : Msg
        data class StateChanged(val state: LocationNotesManager.State) : Msg
        data class ErrorMessageChanged(val message: String?) : Msg
        data class InitialLoadCompleteChanged(val complete: Boolean) : Msg
        data class LocationNamesChanged(val names: Map<GeohashChannelLevel, String>) : Msg
        data class AvailableChannelsChanged(val channels: List<com.bitchat.android.geohash.GeohashChannel>) : Msg
        data class NicknameChanged(val nickname: String) : Msg
    }

    sealed interface Label
}
