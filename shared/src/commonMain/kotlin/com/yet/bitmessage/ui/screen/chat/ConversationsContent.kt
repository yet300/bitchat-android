@file:OptIn(ExperimentalTime::class)

package com.yet.bitmessage.ui.screen.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.domain.model.Peer
import com.app.domain.model.Reachability
import com.app.domain.model.TransportKind
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yet.bitmessage.feature.chats.conversations.ConversationTimeLabel
import com.yet.bitmessage.feature.chats.conversations.ConversationsComponent
import com.yet.bitmessage.feature.chats.conversations.conversationTimeLabel
import com.yet.bitmessage.shared.resources.Res
import com.yet.bitmessage.shared.resources.connectivity_banner_action
import com.yet.bitmessage.shared.resources.connectivity_banner_dismiss
import com.yet.bitmessage.shared.resources.connectivity_banner_title
import com.yet.bitmessage.shared.resources.connectivity_bluetooth
import com.yet.bitmessage.shared.resources.connectivity_internet
import com.yet.bitmessage.shared.resources.connectivity_title
import com.yet.bitmessage.shared.resources.connectivity_tor
import com.yet.bitmessage.shared.resources.connectivity_wifi_aware
import com.yet.bitmessage.shared.resources.conversations_empty
import com.yet.bitmessage.shared.resources.conversations_yesterday
import com.yet.bitmessage.shared.resources.conversations_mute
import com.yet.bitmessage.shared.resources.conversations_nearby
import com.yet.bitmessage.shared.resources.conversations_pin
import com.yet.bitmessage.shared.resources.conversations_search
import com.yet.bitmessage.shared.resources.conversations_title
import com.yet.bitmessage.shared.resources.conversations_unmute
import com.yet.bitmessage.shared.resources.conversations_unpin
import com.yet.bitmessage.ui.component.button.IconCircleButton
import com.yet.bitmessage.ui.component.icon.BellOffRegular
import com.yet.bitmessage.ui.component.icon.Close
import com.yet.bitmessage.ui.component.icon.MoreVert
import com.yet.bitmessage.ui.component.icon.PushPin
import com.yet.bitmessage.ui.component.icon.Search
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsContent(component: ConversationsComponent, modifier: Modifier = Modifier) {
    val model by component.model.subscribeAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(Res.string.conversations_title)) },
                actions = {
                    IconCircleButton(
                        icon = Search,
                        contentDescription = stringResource(Res.string.conversations_search),
                        onClick = component::onSearchClicked,
                    )
                    IconCircleButton(
                        icon = MoreVert,
                        contentDescription = stringResource(Res.string.connectivity_title),
                        onClick = component::onConnectivityClicked,
                    )
                },
            )
        },
    ) { padding ->
        if (model.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            model.connectivityBanner?.let { banner ->
                ConnectivityBanner(
                    banner = banner,
                    onReview = component::onConnectivityClicked,
                    onDismiss = component::onDismissBanner,
                )
            }
            if (model.nearby.isNotEmpty()) {
                NearbyRail(peers = model.nearby, onClick = component::onNearbyClicked)
            }
            Box(modifier = Modifier.fillMaxSize()) {
                if (model.conversations.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.conversations_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    ConversationList(component, model)
                }
            }
        }
    }
}

/**
 * Dismissible prompt shown atop the list when one or more transports need a runtime permission.
 * Tapping the body or "Review" opens the connectivity sheet (the actual re-enable flow lives there);
 * the close button hides it for the session.
 */
@Composable
private fun ConnectivityBanner(
    banner: ConversationsComponent.ConnectivityBanner,
    onReview: () -> Unit,
    onDismiss: () -> Unit,
) {
    // stringResource cannot run inside a lambda, so resolve every kind's label up front, then join.
    val labels = mapOf(
        TransportKind.BLUETOOTH to stringResource(Res.string.connectivity_bluetooth),
        TransportKind.WIFI_AWARE to stringResource(Res.string.connectivity_wifi_aware),
        TransportKind.INTERNET to stringResource(Res.string.connectivity_internet),
        TransportKind.TOR to stringResource(Res.string.connectivity_tor),
    )
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onReview),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.connectivity_banner_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = banner.transports.mapNotNull { labels[it] }.joinToString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            TextButton(onClick = onReview) {
                Text(text = stringResource(Res.string.connectivity_banner_action))
            }
            IconCircleButton(
                icon = Close,
                contentDescription = stringResource(Res.string.connectivity_banner_dismiss),
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun NearbyRail(peers: List<Peer>, onClick: (Peer) -> Unit) {
    Column {
        Text(
            text = stringResource(Res.string.conversations_nearby),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(peers, key = { it.id.raw }) { peer ->
                val name = peer.nickname.ifBlank { peer.id.raw.take(6) }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onClick(peer) }
                        .padding(vertical = 4.dp),
                ) {
                    ConversationAvatar(title = name, reachability = Reachability.NEARBY)
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationList(
    component: ConversationsComponent,
    model: ConversationsComponent.Model,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(model.conversations, key = { it.conversation.id.toString() }) { row ->
            val conversation = row.conversation
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                ListItem(
                    leadingContent = {
                        ConversationAvatar(title = conversation.title, reachability = row.reachability)
                    },
                    headlineContent = {
                        Text(text = conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = conversation.lastMessage?.let { last ->
                        {
                            Text(text = last.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                if (row.isMuted) {
                                    Icon(
                                        imageVector = BellOffRegular,
                                        contentDescription = stringResource(Res.string.conversations_unmute),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                                if (row.isPinned) {
                                    Icon(
                                        imageVector = PushPin,
                                        contentDescription = stringResource(Res.string.conversations_unpin),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                                Text(
                                    text = conversation.lastActivity.timeLabelText(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (conversation.unreadCount > 0 && !row.isMuted) {
                                Badge(modifier = Modifier.padding(top = 4.dp)) {
                                    Text(text = conversation.unreadCount.toString())
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { component.onConversationClicked(conversation.id) },
                            onLongClick = { menuOpen = true },
                        )
                        .padding(horizontal = 4.dp),
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (row.isPinned) Res.string.conversations_unpin else Res.string.conversations_pin,
                                ),
                            )
                        },
                        leadingIcon = { Icon(imageVector = PushPin, contentDescription = null) },
                        onClick = { menuOpen = false; component.onTogglePin(conversation.id) },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (row.isMuted) Res.string.conversations_unmute else Res.string.conversations_mute,
                                ),
                            )
                        },
                        leadingIcon = { Icon(imageVector = BellOffRegular, contentDescription = null) },
                        onClick = { menuOpen = false; component.onToggleMute(conversation.id) },
                    )
                }
            }
        }
    }
}

/** Relative timestamp for a chat row: HH:mm today, "Yesterday", else a date. */
@Composable
private fun Instant?.timeLabelText(): String =
    when (val label = conversationTimeLabel(this, Clock.System.now(), TimeZone.currentSystemDefault())) {
        is ConversationTimeLabel.Today -> "${label.hour.pad2()}:${label.minute.pad2()}"
        ConversationTimeLabel.Yesterday -> stringResource(Res.string.conversations_yesterday)
        is ConversationTimeLabel.Earlier ->
            if (label.currentYear) "${label.day.pad2()}.${label.month.pad2()}"
            else "${label.day.pad2()}.${label.month.pad2()}.${(label.year % 100).pad2()}"
        ConversationTimeLabel.None -> ""
    }

private fun Int.pad2(): String = toString().padStart(2, '0')

/**
 * Deterministic colored avatar (hue from the title) with a reachability presence dot:
 * green = nearby (mesh), cyan = via Nostr, grey = offline.
 */
@Composable
internal fun ConversationAvatar(title: String, reachability: Reachability) {
    val hue = (abs(title.hashCode()) % 360).toFloat()
    val avatarColor = Color.hsv(hue, saturation = 0.45f, value = 0.55f)
    val initial = title.trimStart('#', '@', ' ').firstOrNull()?.uppercase() ?: "?"

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).clip(CircleShape).background(avatarColor),
        ) {
            Text(
                text = initial,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }
        val dot = reachability.dotColor()
        if (dot != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(dot),
            )
        }
    }
}

@Composable
private fun Reachability.dotColor(): Color? = when (this) {
    Reachability.NEARBY -> Color(0xFF39FF14)   // mesh — bright green
    Reachability.INTERNET -> Color(0xFF22B8CF)  // Nostr — cyan
    Reachability.OFFLINE -> null                // no dot
}
