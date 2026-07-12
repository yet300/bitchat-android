package com.yet.bitmessage.ui.screen.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yet.bitmessage.feature.chats.conversations.groups.chat.GroupChatComponent
import com.yet.bitmessage.shared.resources.Res
import com.yet.bitmessage.shared.resources.groups_back
import com.yet.bitmessage.shared.resources.groups_message_hint
import com.yet.bitmessage.shared.resources.groups_send
import com.yet.bitmessage.shared.resources.groups_you
import com.yet.bitmessage.ui.component.button.IconCircleButton
import com.yet.bitmessage.ui.component.icon.ArrowBack
import com.yet.bitmessage.ui.component.icon.Send
import org.jetbrains.compose.resources.stringResource

/** One private group's live conversation: inbound messages plus local echoes of what we send. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupChatContent(component: GroupChatComponent) {
    val model by component.model.subscribeAsState()
    var draft by remember { mutableStateOf("") }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                navigationIcon = {
                    IconCircleButton(
                        icon = ArrowBack,
                        contentDescription = stringResource(Res.string.groups_back),
                        onClick = component::onBackClicked,
                    )
                },
                title = { Text(text = model.title) },
            )

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(model.messages, key = { it.id }) { message -> MessageBubble(message) }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(stringResource(Res.string.groups_message_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconCircleButton(
                    icon = Send,
                    contentDescription = stringResource(Res.string.groups_send),
                    enabled = draft.isNotBlank(),
                    onClick = {
                        component.onSend(draft)
                        draft = ""
                    },
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: GroupChatComponent.Message) {
    val alignment = if (message.isMine) Alignment.End else Alignment.Start
    val bubbleColor =
        if (message.isMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.wrapContentWidth(alignment).align(alignment),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = if (message.isMine) stringResource(Res.string.groups_you) else message.senderNickname,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Start,
                )
                Text(text = message.content, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
