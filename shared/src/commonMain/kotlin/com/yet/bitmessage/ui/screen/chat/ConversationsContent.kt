package com.yet.bitmessage.ui.screen.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yet.bitmessage.feature.chats.conversations.ConversationsComponent
import com.yet.bitmessage.shared.resources.Res
import com.yet.bitmessage.shared.resources.conversations_empty
import com.yet.bitmessage.shared.resources.conversations_search
import com.yet.bitmessage.shared.resources.conversations_search_close
import com.yet.bitmessage.shared.resources.conversations_search_empty
import com.yet.bitmessage.shared.resources.conversations_search_hint
import com.yet.bitmessage.shared.resources.conversations_title
import com.yet.bitmessage.ui.component.button.IconCircleButton
import com.yet.bitmessage.ui.component.icon.Close
import com.yet.bitmessage.ui.component.icon.Search
import org.jetbrains.compose.resources.stringResource

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
                    },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                model.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                model.conversations.isEmpty() -> Text(
                    text = stringResource(
                        if (model.query.isBlank()) Res.string.conversations_empty
                        else Res.string.conversations_search_empty,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> ConversationList(component, model)
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
        items(model.conversations, key = { it.id.toString() }) { conversation ->
            ListItem(
                headlineContent = {
                    Text(text = conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = conversation.lastMessage?.let { last ->
                    {
                        Text(text = last.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                trailingContent = if (conversation.unreadCount > 0) {
                    {
                        Badge { Text(text = conversation.unreadCount.toString()) }
                    }
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { component.onConversationClicked(conversation.id) }
                    .padding(horizontal = 4.dp),
            )
        }
    }
}
