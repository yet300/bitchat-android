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
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yet.bitmessage.feature.chats.conversations.ConversationsComponent
import com.yet.bitmessage.shared.resources.Res
import com.yet.bitmessage.shared.resources.conversations_empty
import org.jetbrains.compose.resources.stringResource

@Composable
fun ConversationsContent(component: ConversationsComponent, modifier: Modifier = Modifier) {
    val model by component.model.subscribeAsState()

    when {
        model.isLoading -> Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        model.conversations.isEmpty() -> Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(Res.string.conversations_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        else -> LazyColumn(modifier = modifier.fillMaxSize()) {
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
}
