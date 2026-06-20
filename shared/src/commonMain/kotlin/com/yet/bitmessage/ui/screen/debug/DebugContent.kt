package com.yet.bitmessage.ui.screen.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yet.bitmessage.feature.debug.DebugComponent
import com.yet.bitmessage.shared.resources.Res
import com.yet.bitmessage.shared.resources.debug_gatt_client
import com.yet.bitmessage.shared.resources.debug_gatt_server
import com.yet.bitmessage.shared.resources.debug_packet_relay
import com.yet.bitmessage.shared.resources.debug_refresh
import com.yet.bitmessage.shared.resources.debug_seen_cap
import com.yet.bitmessage.shared.resources.debug_status
import com.yet.bitmessage.shared.resources.debug_title
import com.yet.bitmessage.shared.resources.debug_verbose
import com.yet.bitmessage.shared.resources.settings_close
import com.yet.bitmessage.ui.component.button.IconCircleButton
import com.yet.bitmessage.ui.component.icon.ArrowBack
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private const val SEEN_CAP_STEP = 100

/** Debug tooling screen (P24): GATT toggles, logging/relay switches, seen-packet cap, live status. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugContent(component: DebugComponent, modifier: Modifier = Modifier) {
    val model by component.model.subscribeAsState()
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconCircleButton(
                            icon = ArrowBack,
                            contentDescription = stringResource(Res.string.settings_close),
                            onClick = component::onCloseClicked,
                        )
                    },
                    title = { Text(text = stringResource(Res.string.debug_title)) },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
                SwitchRow(Res.string.debug_gatt_server, model.gattServerEnabled, component::onGattServerToggled)
                SwitchRow(Res.string.debug_gatt_client, model.gattClientEnabled, component::onGattClientToggled)
                SwitchRow(Res.string.debug_packet_relay, model.packetRelayEnabled, component::onPacketRelayToggled)
                SwitchRow(Res.string.debug_verbose, model.verboseLogging, component::onVerboseToggled)
                HorizontalDivider()

                ListItem(
                    headlineContent = { Text(text = stringResource(Res.string.debug_seen_cap)) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                component.onSeenCapacityChanged((model.seenPacketCapacity - SEEN_CAP_STEP).coerceAtLeast(0))
                            }) { Text("−") }
                            Text(text = model.seenPacketCapacity.toString())
                            TextButton(onClick = {
                                component.onSeenCapacityChanged(model.seenPacketCapacity + SEEN_CAP_STEP)
                            }) { Text("+") }
                        }
                    },
                )
                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = stringResource(Res.string.debug_status), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = component::onRefreshStatus) { Text(text = stringResource(Res.string.debug_refresh)) }
                }
                Text(
                    text = model.status,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: StringResource, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(text = stringResource(label)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}
