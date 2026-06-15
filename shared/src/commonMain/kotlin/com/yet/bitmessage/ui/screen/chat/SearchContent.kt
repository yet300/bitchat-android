package com.yet.bitmessage.ui.screen.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.domain.model.ConversationId
import com.app.domain.model.Reachability
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yet.bitmessage.feature.chats.conversations.search.SearchComponent
import com.yet.bitmessage.feature.chats.conversations.search.SearchTab
import com.yet.bitmessage.shared.resources.Res
import com.yet.bitmessage.shared.resources.search_close
import com.yet.bitmessage.shared.resources.search_empty
import com.yet.bitmessage.shared.resources.search_hint
import com.yet.bitmessage.shared.resources.search_prompt
import com.yet.bitmessage.shared.resources.search_tab_channels
import com.yet.bitmessage.shared.resources.search_tab_chats
import com.yet.bitmessage.shared.resources.search_tab_messages
import com.yet.bitmessage.shared.resources.search_tab_people
import com.yet.bitmessage.ui.component.button.IconCircleButton
import com.yet.bitmessage.ui.component.icon.Close
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Unified full-screen search (D3): a query field, the Chats/People/Messages/Channels tabs and the
 * active tab's results. A tapped result is handed to the component, which deep-links to the
 * conversation and closes the overlay. System back is handled by the hosting slot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchContent(component: SearchComponent, modifier: Modifier = Modifier) {
    val model by component.model.subscribeAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                navigationIcon = {
                    IconCircleButton(
                        icon = Close,
                        contentDescription = stringResource(Res.string.search_close),
                        onClick = component::onCloseClicked,
                    )
                },
                title = {
                    TextField(
                        value = model.query,
                        onValueChange = component::onQueryChanged,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        singleLine = true,
                        placeholder = { Text(text = stringResource(Res.string.search_hint)) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                },
            )

            val tabs = SearchTab.entries
            TabRow(selectedTabIndex = tabs.indexOf(model.tab)) {
                tabs.forEach { tab ->
                    Tab(
                        selected = tab == model.tab,
                        onClick = { component.onTabSelected(tab) },
                        text = { Text(text = stringResource(tab.label())) },
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (!model.isActive) {
                    CenteredHint(stringResource(Res.string.search_prompt))
                } else {
                    SearchResults(model, component::onResultClicked)
                }
            }
        }
    }
}

@Composable
private fun BoxScope.CenteredHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.align(Alignment.Center).padding(24.dp),
    )
}

@Composable
private fun BoxScope.SearchResults(
    model: SearchComponent.Model,
    onResultClicked: (ConversationId) -> Unit,
) {
    val empty = when (model.tab) {
        SearchTab.CHATS -> model.chats.isEmpty()
        SearchTab.PEOPLE -> model.people.isEmpty()
        SearchTab.MESSAGES -> model.messages.isEmpty()
        SearchTab.CHANNELS -> model.channels.isEmpty()
    }
    if (empty) {
        CenteredHint(stringResource(Res.string.search_empty))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        when (model.tab) {
            SearchTab.CHATS -> items(model.chats, key = { it.id.toString() }) { conversation ->
                ResultRow(
                    title = conversation.title,
                    subtitle = conversation.lastMessage?.content,
                    reachability = Reachability.OFFLINE,
                    onClick = { onResultClicked(conversation.id) },
                )
            }
            SearchTab.PEOPLE -> items(model.people, key = { it.conversationId.toString() }) { person ->
                ResultRow(
                    title = person.name,
                    subtitle = null,
                    reachability = if (person.isOnline) Reachability.NEARBY else Reachability.OFFLINE,
                    onClick = { onResultClicked(person.conversationId) },
                )
            }
            SearchTab.MESSAGES -> items(model.messages, key = { it.id }) { message ->
                ResultRow(
                    title = message.sender.displayName,
                    subtitle = message.content,
                    reachability = Reachability.OFFLINE,
                    onClick = { onResultClicked(message.conversationId) },
                )
            }
            SearchTab.CHANNELS -> items(model.channels, key = { it.tag }) { channel ->
                ResultRow(
                    title = channel.tag,
                    subtitle = null,
                    reachability = Reachability.OFFLINE,
                    onClick = { onResultClicked(ConversationId.Channel(channel.tag)) },
                )
            }
        }
    }
}

@Composable
private fun ResultRow(
    title: String,
    subtitle: String?,
    reachability: Reachability,
    onClick: () -> Unit,
) {
    ListItem(
        leadingContent = { ConversationAvatar(title = title, reachability = reachability) },
        headlineContent = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = subtitle?.let {
            { Text(text = it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

private fun SearchTab.label(): StringResource = when (this) {
    SearchTab.CHATS -> Res.string.search_tab_chats
    SearchTab.PEOPLE -> Res.string.search_tab_people
    SearchTab.MESSAGES -> Res.string.search_tab_messages
    SearchTab.CHANNELS -> Res.string.search_tab_channels
}
