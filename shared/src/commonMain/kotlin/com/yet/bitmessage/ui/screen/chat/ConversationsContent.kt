@file:OptIn(ExperimentalTime::class)

package com.yet.bitmessage.ui.screen.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yet.bitmessage.feature.chats.conversations.ConversationTimeLabel
import com.yet.bitmessage.feature.chats.conversations.ConversationsComponent
import com.yet.bitmessage.feature.chats.conversations.conversationTimeLabel
import com.yet.bitmessage.shared.resources.Res
import com.yet.bitmessage.shared.resources.connectivity_title
import com.yet.bitmessage.shared.resources.conversations_empty
import com.yet.bitmessage.shared.resources.conversations_yesterday
import com.yet.bitmessage.shared.resources.conversations_nearby
import com.yet.bitmessage.shared.resources.conversations_search
import com.yet.bitmessage.shared.resources.conversations_search_close
import com.yet.bitmessage.shared.resources.conversations_search_empty
import com.yet.bitmessage.shared.resources.conversations_search_hint
import com.yet.bitmessage.shared.resources.conversations_title
import com.yet.bitmessage.ui.component.button.IconCircleButton
import com.yet.bitmessage.ui.component.icon.Close
import com.yet.bitmessage.ui.component.icon.MoreVert
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
    var searching by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (searching) {
                SearchBar(
                    query = model.query,
                    onQueryChanged = component::onQueryChanged,
                    onClose = {
                        component.onQueryChanged("")
                        searching = false
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(text = stringResource(Res.string.conversations_title)) },
                    actions = {
                        IconCircleButton(
                            icon = Search,
                            contentDescription = stringResource(Res.string.conversations_search),
                            onClick = { searching = true },
                        )
                        IconCircleButton(
                            icon = MoreVert,
                            contentDescription = stringResource(Res.string.connectivity_title),
                            onClick = component::onConnectivityClicked,
                        )
                    },
                )
            }
        },
    ) { padding ->
        if (model.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!searching && model.nearby.isNotEmpty()) {
                NearbyRail(peers = model.nearby, onClick = component::onNearbyClicked)
            }
            Box(modifier = Modifier.fillMaxSize()) {
                if (model.conversations.isEmpty()) {
                    Text(
                        text = stringResource(
                            if (model.query.isBlank()) Res.string.conversations_empty
                            else Res.string.conversations_search_empty,
                        ),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    onClose: () -> Unit,
) {
    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(text = stringResource(Res.string.conversations_search_hint)) },
            )
        },
        navigationIcon = {
            IconCircleButton(
                icon = Close,
                contentDescription = stringResource(Res.string.conversations_search_close),
                onClick = onClose,
            )
        },
    )
}

@Composable
private fun ConversationList(
    component: ConversationsComponent,
    model: ConversationsComponent.Model,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(model.conversations, key = { it.conversation.id.toString() }) { row ->
            val conversation = row.conversation
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
                        Text(
                            text = conversation.lastActivity.timeLabelText(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (conversation.unreadCount > 0) {
                            Badge(modifier = Modifier.padding(top = 4.dp)) {
                                Text(text = conversation.unreadCount.toString())
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { component.onConversationClicked(conversation.id) }
                    .padding(horizontal = 4.dp),
            )
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
