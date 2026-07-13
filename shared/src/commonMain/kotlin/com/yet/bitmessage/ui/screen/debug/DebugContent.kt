package com.yet.bitmessage.ui.screen.debug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.domain.model.MeshTopology
import com.app.domain.model.PacketLogEntry
import com.app.domain.model.PacketLogKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yet.bitmessage.feature.debug.DebugComponent
import com.yet.bitmessage.shared.resources.Res
import com.yet.bitmessage.shared.resources.debug_gatt_client
import com.yet.bitmessage.shared.resources.debug_gatt_server
import com.yet.bitmessage.shared.resources.debug_mesh_graph
import com.yet.bitmessage.shared.resources.debug_mesh_graph_empty
import com.yet.bitmessage.shared.resources.debug_ping
import com.yet.bitmessage.shared.resources.debug_ping_button
import com.yet.bitmessage.shared.resources.debug_ping_empty
import com.yet.bitmessage.shared.resources.debug_pinging
import com.yet.bitmessage.shared.resources.debug_packet_log
import com.yet.bitmessage.shared.resources.debug_packet_log_empty
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
                HorizontalDivider()

                Text(
                    text = stringResource(Res.string.debug_mesh_graph),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                )
                MeshGraph(model.topology)
                HorizontalDivider()

                Text(
                    text = stringResource(Res.string.debug_ping),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                )
                PingSection(
                    topology = model.topology,
                    isPinging = model.isPinging,
                    result = model.pingResult,
                    onPing = component::onPingClicked,
                )
                HorizontalDivider()

                Text(
                    text = stringResource(Res.string.debug_packet_log),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                )
                PacketLog(model.packetLog)
            }
        }
    }
}

/** Directed echo probe (0x26/27): ping each known neighbour, show RTT / hop count. */
@Composable
private fun PingSection(
    topology: MeshTopology,
    isPinging: Boolean,
    result: String?,
    onPing: (String) -> Unit,
) {
    if (topology.nodes.isEmpty()) {
        Text(
            text = stringResource(Res.string.debug_ping_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        return
    }
    topology.nodes.forEach { node ->
        ListItem(
            headlineContent = { Text(node.nickname ?: node.peerId) },
            supportingContent = { Text(node.peerId, style = MaterialTheme.typography.labelSmall) },
            trailingContent = {
                TextButton(enabled = !isPinging, onClick = { onPing(node.peerId) }) {
                    Text(stringResource(Res.string.debug_ping_button))
                }
            },
        )
    }
    val line = when {
        isPinging -> stringResource(Res.string.debug_pinging)
        result != null -> result
        else -> null
    }
    if (line != null) {
        Text(
            text = line,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** Neighbour graph on a ring layout: nodes evenly on a circle, confirmed edges bold, claims faint. */
@Composable
private fun MeshGraph(topology: MeshTopology, modifier: Modifier = Modifier) {
    if (topology.nodes.isEmpty()) {
        Text(
            text = stringResource(Res.string.debug_mesh_graph_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        return
    }
    val measurer = rememberTextMeasurer()
    val confirmedColor = MaterialTheme.colorScheme.primary
    val claimColor = MaterialTheme.colorScheme.outline
    val nodeColor = MaterialTheme.colorScheme.tertiary
    val labelColor = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.fillMaxWidth().height(220.dp).padding(16.dp)) {
        val nodes = topology.nodes
        val center = Offset(size.width / 2f, size.height / 2f)
        val ringRadius = min(center.x, center.y) * 0.72f
        val pos = nodes.mapIndexed { i, node ->
            val angle = 2.0 * PI * i / nodes.size - PI / 2.0
            node.peerId to Offset(
                center.x + ringRadius * cos(angle).toFloat(),
                center.y + ringRadius * sin(angle).toFloat(),
            )
        }.toMap()

        topology.edges.forEach { edge ->
            val a = pos[edge.a] ?: return@forEach
            val b = pos[edge.b] ?: return@forEach
            drawLine(
                color = if (edge.confirmed) confirmedColor else claimColor,
                start = a,
                end = b,
                strokeWidth = if (edge.confirmed) 3f else 1.5f,
                alpha = if (edge.confirmed) 1f else 0.5f,
            )
        }
        nodes.forEach { node ->
            val p = pos[node.peerId] ?: return@forEach
            drawCircle(color = nodeColor, radius = 9f, center = p)
            val label = node.nickname ?: node.peerId.take(6)
            val layout = measurer.measure(label, style = TextStyle(fontSize = 10.sp, color = labelColor))
            drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y + 12f))
        }
    }
}

private const val PACKET_LOG_LIMIT = 100

/** Newest-first tail of the traffic log; capped because it renders inside the parent's scroll. */
@Composable
private fun PacketLog(entries: List<PacketLogEntry>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) {
        Text(
            text = stringResource(Res.string.debug_packet_log_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        return
    }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        entries.asReversed().take(PACKET_LOG_LIMIT).forEach { entry ->
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = entry.kind.color(),
                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun PacketLogKind.color() = when (this) {
    PacketLogKind.SYSTEM -> MaterialTheme.colorScheme.onSurfaceVariant
    PacketLogKind.PEER -> MaterialTheme.colorScheme.tertiary
    PacketLogKind.PACKET -> MaterialTheme.colorScheme.primary
    PacketLogKind.RELAY -> MaterialTheme.colorScheme.secondary
}

@Composable
private fun SwitchRow(label: StringResource, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(text = stringResource(label)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}
