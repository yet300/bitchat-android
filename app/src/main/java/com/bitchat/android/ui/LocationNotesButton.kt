package com.bitchat.android.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.LocationChannelManager

/**
 * Location Notes button component for MainHeader
 * Shows in mesh mode when location permission granted AND services enabled
 * Icon turns primary color when notes exist, gray otherwise
 */
@Composable
fun LocationNotesButton(
    selectedLocationChannel: ChannelID?,
    permissionState: LocationChannelManager.PermissionState,
    locationServicesEnabled: Boolean,
    notesCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    // Check both permission AND location services enabled
    val locationPermissionGranted = permissionState == LocationChannelManager.PermissionState.AUTHORIZED
    val locationEnabled = locationPermissionGranted && locationServicesEnabled

    // Only show in mesh mode when location is authorized (iOS pattern)
    if (selectedLocationChannel is ChannelID.Mesh && locationEnabled) {
        val hasNotes = notesCount > 0
        IconButton(
            onClick = onClick,
            modifier = modifier.size(24.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Description, // "long.text.page.and.pencil" equivalent
                contentDescription = stringResource(R.string.cd_location_notes),
                modifier = Modifier.size(16.dp),
                tint = if (hasNotes) colorScheme.primary else Color.Gray
            )
        }
    }
}
