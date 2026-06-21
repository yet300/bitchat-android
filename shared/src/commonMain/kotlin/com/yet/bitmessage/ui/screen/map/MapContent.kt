package com.yet.bitmessage.ui.screen.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yet.bitmessage.feature.map.MapComponent
import com.yet.bitmessage.shared.resources.Res
import com.yet.bitmessage.shared.resources.map_hint
import com.yet.bitmessage.shared.resources.map_title
import com.yet.bitmessage.shared.resources.search_close
import com.yet.bitmessage.shared.resources.search_geo_action
import com.yet.bitmessage.ui.component.GeohashMapPicker
import com.yet.bitmessage.ui.component.button.IconCircleButton
import com.yet.bitmessage.ui.component.icon.ArrowBack
import org.jetbrains.compose.resources.stringResource

/** Root-level full-screen map geohash picker (P6): tap to choose a location, then confirm to teleport. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapContent(component: MapComponent, modifier: Modifier = Modifier) {
    val model by component.model.subscribeAsState()
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconCircleButton(
                            icon = ArrowBack,
                            contentDescription = stringResource(Res.string.search_close),
                            onClick = component::onCloseClicked,
                        )
                    },
                    title = { Text(text = stringResource(Res.string.map_title)) },
                )
            },
            bottomBar = {
                Surface(tonalElevation = 2.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = model.selectedGeohash?.let { "#$it" } ?: stringResource(Res.string.map_hint),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (model.selectedGeohash == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Button(onClick = component::onConfirmClicked, enabled = model.canConfirm) {
                            Text(text = stringResource(Res.string.search_geo_action))
                        }
                    }
                }
            },
        ) { padding ->
            GeohashMapPicker(
                initialGeohash = model.initialGeohash,
                selectedGeohash = model.selectedGeohash,
                onTap = component::onMapTapped,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}
