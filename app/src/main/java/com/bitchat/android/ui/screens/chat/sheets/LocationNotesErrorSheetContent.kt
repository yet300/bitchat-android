package com.bitchat.android.ui.screens.chat.sheets

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bitchat.android.feature.chat.sheet.locationnotes.LocationNotesComponent

@Composable
fun LocationNotesErrorSheetContent(
    component: LocationNotesComponent,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Location Unavailable",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Location permission is required for notes",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = {
            // UNIFIED FIX: Enable location services first (user toggle)
            component.onEnableLocationServices()
            // Then request location channels (which will also request permission if needed)
            component.onRequestLocationPermission()
            component.onRefreshLocationChannels()
        }) {
            Text("Enable Location")
        }
    }
}
