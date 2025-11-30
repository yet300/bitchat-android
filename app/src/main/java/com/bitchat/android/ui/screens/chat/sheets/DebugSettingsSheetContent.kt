package com.bitchat.android.ui.screens.chat.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.bitchat.android.R
import com.bitchat.android.feature.debug.DebugComponent

/**
 * Debug Settings sheet content - uses DebugComponent instead of ChatViewModel
 * This is just the content, the ModalBottomSheet wrapper is in ChatScreen
 */
@Composable
fun DebugSettingsSheetContent(
    component: DebugComponent,
    lazyListState: LazyListState,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val model by component.model.subscribeAsState()

    LazyColumn(
        state = lazyListState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 80.dp, bottom = 20.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.BugReport, contentDescription = null, tint = Color(0xFFFF9500))
                Text(stringResource(R.string.debug_tools), fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
            Text(
                text = stringResource(R.string.debug_tools_desc),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }

        // Verbose logging toggle
        item {
            Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.SettingsEthernet, contentDescription = null, tint = Color(0xFF00C851))
                        Text(stringResource(R.string.debug_verbose_logging), fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Switch(checked = model.verboseLoggingEnabled, onCheckedChange = { component.onToggleVerboseLogging() })
                    }
                    Text("Show detailed logs in chat timeline", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }

        // GATT Server toggle
        item {
            Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, tint = if (model.gattServerEnabled) Color(0xFF00C851) else Color.Gray)
                    Text(stringResource(R.string.debug_gatt_server), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Switch(checked = model.gattServerEnabled, onCheckedChange = { component.onToggleGattServer() })
                }
            }
        }

        // GATT Client toggle
        item {
            Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = if (model.gattClientEnabled) Color(0xFF007AFF) else Color.Gray)
                    Text(stringResource(R.string.debug_gatt_client), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Switch(checked = model.gattClientEnabled, onCheckedChange = { component.onToggleGattClient() })
                }
            }
        }

        // Packet relay toggle with stats
        item {
            Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.SettingsEthernet, contentDescription = null, tint = if (model.packetRelayEnabled) Color(0xFFFF9500) else Color.Gray)
                        Text(stringResource(R.string.debug_packet_relay), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                        Switch(checked = model.packetRelayEnabled, onCheckedChange = { component.onTogglePacketRelay() })
                    }
                    Text(stringResource(R.string.debug_since_start_fmt, model.relayStats.totalRelaysCount), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text(stringResource(R.string.debug_relays_window_fmt, model.relayStats.last10SecondRelays, model.relayStats.lastMinuteRelays, model.relayStats.last15MinuteRelays), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }

        // GATT controls with connection limits
        item {
            Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = Color(0xFF007AFF))
                        Text(stringResource(R.string.debug_bluetooth_roles), fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    
                    // Server controls
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.debug_gatt_server), fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        Switch(checked = model.gattServerEnabled, onCheckedChange = { component.onToggleGattServer() })
                    }
                    val serverCount = model.connectedDevices.count { it.connectionType.toString().contains("SERVER") }
                    Text(stringResource(R.string.debug_connections_fmt, serverCount, model.maxServerConnections), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.debug_max_server), fontFamily = FontFamily.Monospace, modifier = Modifier.width(90.dp), fontSize = 12.sp)
                        Slider(
                            value = model.maxServerConnections.toFloat(),
                            onValueChange = { component.onUpdateMaxServerConnections(it.toInt().coerceAtLeast(1)) },
                            valueRange = 1f..32f,
                            steps = 30,
                            modifier = Modifier.weight(1f)
                        )
                        Text("${model.maxServerConnections}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.width(30.dp))
                    }
                    
                    // Client controls
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.debug_gatt_client), fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        Switch(checked = model.gattClientEnabled, onCheckedChange = { component.onToggleGattClient() })
                    }
                    val clientCount = model.connectedDevices.count { it.connectionType.toString().contains("CLIENT") }
                    Text(stringResource(R.string.debug_connections_fmt, clientCount, model.maxClientConnections), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.debug_max_client), fontFamily = FontFamily.Monospace, modifier = Modifier.width(90.dp), fontSize = 12.sp)
                        Slider(
                            value = model.maxClientConnections.toFloat(),
                            onValueChange = { component.onUpdateMaxClientConnections(it.toInt().coerceAtLeast(1)) },
                            valueRange = 1f..32f,
                            steps = 30,
                            modifier = Modifier.weight(1f)
                        )
                        Text("${model.maxClientConnections}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.width(30.dp))
                    }
                    
                    // Overall limit
                    val overallCount = model.connectedDevices.size
                    Text(stringResource(R.string.debug_overall_connections_fmt, overallCount, model.maxConnectionsOverall), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.debug_max_overall), fontFamily = FontFamily.Monospace, modifier = Modifier.width(90.dp), fontSize = 12.sp)
                        Slider(
                            value = model.maxConnectionsOverall.toFloat(),
                            onValueChange = { component.onUpdateMaxConnectionsOverall(it.toInt().coerceAtLeast(1)) },
                            valueRange = 1f..32f,
                            steps = 30,
                            modifier = Modifier.weight(1f)
                        )
                        Text("${model.maxConnectionsOverall}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.width(30.dp))
                    }
                    
                    Text(stringResource(R.string.debug_roles_hint), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }
        }
        
        // Sync settings
        item {
            Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.SettingsEthernet, contentDescription = null, tint = Color(0xFF9C27B0))
                        Text("sync settings", fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    
                    Text(stringResource(R.string.debug_max_packets_per_sync_fmt, model.seenPacketCapacity), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                    Slider(
                        value = model.seenPacketCapacity.toFloat(),
                        onValueChange = { component.onUpdateSeenPacketCapacity(it.toInt()) },
                        valueRange = 10f..1000f,
                        steps = 99
                    )
                    
                    Text(stringResource(R.string.debug_max_gcs_filter_size_fmt, model.gcsMaxBytes), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                    Slider(
                        value = model.gcsMaxBytes.toFloat(),
                        onValueChange = { component.onUpdateGcsMaxBytes(it.toInt()) },
                        valueRange = 128f..1024f,
                        steps = 0
                    )
                    
                    Text(stringResource(R.string.debug_target_fpr_fmt, model.gcsFprPercent), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                    Slider(
                        value = model.gcsFprPercent.toFloat(),
                        onValueChange = { component.onUpdateGcsFprPercent(it.toDouble()) },
                        valueRange = 0.1f..5.0f,
                        steps = 49
                    )
                    
                    val p = remember(model.gcsFprPercent) { com.bitchat.android.sync.GCSFilter.deriveP(model.gcsFprPercent / 100.0) }
                    val nmax = remember(model.gcsFprPercent, model.gcsMaxBytes) { com.bitchat.android.sync.GCSFilter.estimateMaxElementsForSize(model.gcsMaxBytes, p) }
                    Text(stringResource(R.string.debug_derived_p_fmt, p.toString(), nmax.toString()), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }
        }

        // Connected devices
        item {
            Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Devices, contentDescription = null, tint = Color(0xFF4CAF50))
                        Text(stringResource(R.string.debug_connected_devices), fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    
                    model.localAdapterAddress?.let { address ->
                        Text(stringResource(R.string.debug_our_device_id_fmt, address), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                    }
                    
                    if (model.connectedDevices.isEmpty()) {
                        Text("none", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.6f))
                    } else {
                        model.connectedDevices.forEach { device ->
                            Surface(shape = RoundedCornerShape(8.dp), color = colorScheme.surface.copy(alpha = 0.6f)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${device.peerID ?: stringResource(R.string.unknown)} • ${device.deviceAddress}",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp
                                        )
                                        val roleLabel = if (device.connectionType.toString().contains("SERVER")) 
                                            stringResource(R.string.debug_role_server) 
                                        else 
                                            stringResource(R.string.debug_role_client)
                                        Text(
                                            text = "${device.nickname ?: ""} • ${stringResource(R.string.debug_rssi_fmt, device.rssi?.toString() ?: stringResource(R.string.debug_question_mark))} • $roleLabel${if (device.isDirectConnection) stringResource(R.string.debug_direct_suffix) else ""}",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = colorScheme.onSurface.copy(alpha = 0.7f)
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.debug_disconnect),
                                        color = Color(0xFFBF1A1A),
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier
                                            .padding(start = 8.dp)
                                            .clickable { component.onDisconnectDevice(device.deviceAddress) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Scan results
        item {
            Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = Color(0xFF007AFF))
                        Text(stringResource(R.string.debug_recent_scan_results), fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    
                    if (model.scanResults.isEmpty()) {
                        Text(stringResource(R.string.debug_none), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.6f))
                    } else {
                        model.scanResults.forEach { result ->
                            Surface(shape = RoundedCornerShape(8.dp), color = colorScheme.surface.copy(alpha = 0.6f)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${result.peerID ?: stringResource(R.string.unknown)} • ${result.deviceAddress}",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            text = stringResource(R.string.debug_rssi_fmt, result.rssi.toString()),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = colorScheme.onSurface.copy(alpha = 0.7f)
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.debug_connect),
                                        color = Color(0xFF00C851),
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier
                                            .padding(start = 8.dp)
                                            .clickable { component.onConnectToDevice(result.deviceAddress) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Debug console
        item {
            Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.BugReport, contentDescription = null, tint = Color(0xFFFF9500))
                        Text(stringResource(R.string.debug_debug_console), fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = stringResource(R.string.debug_clear),
                            color = Color(0xFFBF1A1A),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.clickable { component.onClearDebugMessages() }
                        )
                    }
                    Column(
                        Modifier
                            .heightIn(max = 260.dp)
                            .background(colorScheme.surface.copy(alpha = 0.5f))
                            .padding(8.dp)
                    ) {
                        model.debugMessages.takeLast(100).reversed().forEach { msg ->
                            Text(
                                text = msg.content,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
