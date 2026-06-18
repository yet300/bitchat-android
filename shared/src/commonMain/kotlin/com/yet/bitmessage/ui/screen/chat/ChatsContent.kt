package com.yet.bitmessage.ui.screen.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yet.bitmessage.ui.component.icon.Done
import com.app.domain.model.TransportKind
import com.app.domain.model.TransportState
import com.app.domain.model.TransportStatus
import com.arkivanov.decompose.extensions.compose.experimental.panels.ChildPanels
import com.arkivanov.decompose.extensions.compose.experimental.panels.HorizontalChildPanelsLayout
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.arkivanov.decompose.router.panels.ChildPanelsMode
import com.yet.bitmessage.feature.chats.conversations.connectivity.ConnectivityComponent
import com.yet.bitmessage.feature.chats.main.ChatsComponent
import com.yet.bitmessage.shared.resources.Res
import com.yet.bitmessage.shared.resources.connectivity_action_enable
import com.yet.bitmessage.shared.resources.connectivity_action_grant
import com.yet.bitmessage.shared.resources.connectivity_bluetooth
import com.yet.bitmessage.shared.resources.connectivity_internet
import com.yet.bitmessage.shared.resources.connectivity_count_peers
import com.yet.bitmessage.shared.resources.connectivity_count_relays
import com.yet.bitmessage.shared.resources.connectivity_peer_direct
import com.yet.bitmessage.shared.resources.connectivity_peer_relayed
import com.yet.bitmessage.shared.resources.connectivity_peer_verified
import com.yet.bitmessage.shared.resources.connectivity_peers_empty
import com.yet.bitmessage.shared.resources.connectivity_peers_title
import com.yet.bitmessage.shared.resources.connectivity_state_off
import com.yet.bitmessage.shared.resources.connectivity_state_on
import com.yet.bitmessage.shared.resources.connectivity_state_permission
import com.yet.bitmessage.shared.resources.connectivity_state_unavailable
import com.yet.bitmessage.shared.resources.connectivity_title
import com.yet.bitmessage.shared.resources.connectivity_tor
import com.yet.bitmessage.shared.resources.connectivity_wifi_aware
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun ChatsContent(component: ChatsComponent, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier) {
        val mode = if (maxWidth >= 800.dp) ChildPanelsMode.DUAL else ChildPanelsMode.SINGLE
        LaunchedEffect(mode) {
            component.setMode(mode)
        }
        ChildPanels(
            panels = component.panels,
            mainChild = { child ->
                when (val main = child.instance) {
                    is ChatsComponent.Main.Conversations -> ConversationsContent(main.component)
                }
            },
            detailsChild = { child ->
                when (val details = child.instance) {
                    is ChatsComponent.Details.Chat -> ChatContent(details.component)
                }
            },
            layout = HorizontalChildPanelsLayout(dualWeights = 0.4f to 0.6f),
        )
    }

    val sheet by component.sheetSlot.subscribeAsState()
    when (val child = sheet.child?.instance) {
        is ChatsComponent.SheetChild.Connectivity ->
            ConnectivitySheet(child.component, onDismiss = component::onDismissSheet)
        is ChatsComponent.SheetChild.Search ->
            SearchContent(child.component)
        is ChatsComponent.SheetChild.Contacts ->
            ContactsContent(child.component)
        is ChatsComponent.SheetChild.Settings ->
            SettingsContent(child.component)
        is ChatsComponent.SheetChild.Channels ->
            ChannelsContent(child.component)
        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectivitySheet(component: ConnectivityComponent, onDismiss: () -> Unit) {
    val model by component.model.subscribeAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Text(
            text = stringResource(Res.string.connectivity_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        model.statuses.forEach { status ->
            TransportRow(status, component::onEnableClicked)
        }

        Text(
            text = stringResource(Res.string.connectivity_peers_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
        )
        if (model.peers.isEmpty()) {
            Text(
                text = stringResource(Res.string.connectivity_peers_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        } else {
            model.peers.forEach { peer ->
                PeerRow(peer, onToggleFavorite = component::onToggleFavorite)
            }
        }
    }
}

@Composable
private fun PeerRow(peer: ConnectivityComponent.PeerRow, onToggleFavorite: (String) -> Unit) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = peer.name)
                if (peer.isVerified) {
                    Icon(
                        imageVector = Done,
                        contentDescription = stringResource(Res.string.connectivity_peer_verified),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        },
        supportingContent = { Text(text = peerDetail(peer)) },
        trailingContent = {
            IconButton(onClick = { onToggleFavorite(peer.peerIdRaw) }) {
                Text(
                    text = if (peer.isFavorite) "★" else "☆",
                    color = if (peer.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun peerDetail(peer: ConnectivityComponent.PeerRow): String {
    val link = stringResource(
        if (peer.isDirect) Res.string.connectivity_peer_direct else Res.string.connectivity_peer_relayed,
    )
    return peer.rssi?.let { "$link · $it dBm" } ?: link
}

@Composable
private fun TransportRow(status: TransportStatus, onEnable: (TransportKind) -> Unit) {
    ListItem(
        headlineContent = { Text(text = stringResource(status.kind.label())) },
        supportingContent = { Text(text = supportingLine(status)) },
        trailingContent = {
            val action = status.state.actionLabel()
            if (action != null) {
                TextButton(onClick = { onEnable(status.kind) }) {
                    Text(text = stringResource(action))
                }
            }
        },
    )
}

/** State label plus a live connected count where meaningful (mesh peers / Nostr relays). */
@Composable
private fun supportingLine(status: TransportStatus): String {
    val base = stringResource(status.state.label())
    val count = status.count ?: return base
    val suffix = when (status.kind) {
        TransportKind.BLUETOOTH -> stringResource(Res.string.connectivity_count_peers, count)
        TransportKind.INTERNET -> stringResource(Res.string.connectivity_count_relays, count)
        else -> return base
    }
    return "$base · $suffix"
}

private fun TransportKind.label(): StringResource = when (this) {
    TransportKind.BLUETOOTH -> Res.string.connectivity_bluetooth
    TransportKind.WIFI_AWARE -> Res.string.connectivity_wifi_aware
    TransportKind.INTERNET -> Res.string.connectivity_internet
    TransportKind.TOR -> Res.string.connectivity_tor
}

private fun TransportState.label(): StringResource = when (this) {
    TransportState.ON -> Res.string.connectivity_state_on
    TransportState.OFF -> Res.string.connectivity_state_off
    TransportState.UNAVAILABLE -> Res.string.connectivity_state_unavailable
    TransportState.PERMISSION_REQUIRED -> Res.string.connectivity_state_permission
}

/** Action label for actionable states; null when there is nothing the user can do. */
private fun TransportState.actionLabel(): StringResource? = when (this) {
    TransportState.OFF -> Res.string.connectivity_action_enable
    TransportState.PERMISSION_REQUIRED -> Res.string.connectivity_action_grant
    TransportState.ON, TransportState.UNAVAILABLE -> null
}
