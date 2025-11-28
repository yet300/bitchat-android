package com.bitchat.android.ui.screens.chat.sheets

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.bitchat.android.feature.chat.locationnotes.LocationNotesComponent
import com.bitchat.android.geohash.GeohashChannelLevel

@Composable
fun LocationNotesSheetPresenterContent(
    component: LocationNotesComponent,
    modifier: Modifier = Modifier,
    lazyListState: LazyListState,
) {
    val model by component.model.subscribeAsState()
    
    // iOS pattern: notesGeohash ?? LocationChannelManager.shared.availableChannels.first(where: { $0.level == .building })?.geohash
    val buildingGeohash = model.availableChannels.firstOrNull { it.level == GeohashChannelLevel.BUILDING }?.geohash
    
    if (buildingGeohash != null) {
        // Set geohash when available
        LaunchedEffect(buildingGeohash) {
            component.onSetGeohash(buildingGeohash)
        }
        
        LocationNotesSheetContent(
            component = component,
            modifier = modifier,
            lazyListState = lazyListState,
        )
    } else {
        // No building geohash available - show error state (matches iOS)
        LocationNotesErrorSheetContent(
            component = component,
            modifier = modifier,
        )
    }
}
