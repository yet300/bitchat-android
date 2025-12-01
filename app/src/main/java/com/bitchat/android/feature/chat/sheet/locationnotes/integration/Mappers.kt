package com.bitchat.android.feature.chat.sheet.locationnotes.integration

import com.bitchat.android.feature.chat.locationnotes.LocationNotesComponent
import com.bitchat.android.feature.chat.locationnotes.store.LocationNotesStore

internal val stateToModel: (LocationNotesStore.State) -> LocationNotesComponent.Model = { state ->
    LocationNotesComponent.Model(
        notes = state.notes,
        geohash = state.geohash,
        state = state.state,
        errorMessage = state.errorMessage,
        initialLoadComplete = state.initialLoadComplete,
        locationNames = state.locationNames,
        availableChannels = state.availableChannels,
        nickname = state.nickname
    )
}
