package com.bitchat.android.ui.screens.chat.sheets

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.ui.ChatViewModel

@Composable
fun LocationNotesSheetPresenterContent(
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel,
    lazyListState: LazyListState,
) {
    val availableChannels by viewModel.availableLocationChannels.observeAsState(emptyList())
    val nickname by viewModel.nickname.observeAsState("")
    
    // iOS pattern: notesGeohash ?? LocationChannelManager.shared.availableChannels.first(where: { $0.level == .building })?.geohash
    val buildingGeohash = availableChannels.firstOrNull { it.level == GeohashChannelLevel.BUILDING }?.geohash
    
    if (buildingGeohash != null) {
        // Get location name from locationManager
        val locationNames by viewModel.locationNames.observeAsState(emptyMap())
        val locationName = locationNames[GeohashChannelLevel.BUILDING]
            ?: locationNames[GeohashChannelLevel.BLOCK]
        
        LocationNotesSheetContent(
            modifier = modifier,
            geohash = buildingGeohash,
            locationName = locationName,
            nickname = nickname,
            viewModel = viewModel,
            lazyListState = lazyListState,
        )
    } else {
        // No building geohash available - show error state (matches iOS)
        LocationNotesErrorSheetContent(
            modifier = modifier,
            viewModel = viewModel,
        )
    }
}
