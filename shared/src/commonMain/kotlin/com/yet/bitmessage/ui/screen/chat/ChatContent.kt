package com.yet.bitmessage.ui.screen.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.domain.model.BitMessage
import com.app.domain.model.DeliveryStatus
import com.app.domain.model.Reachability
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yet.bitmessage.feature.chats.details.ChatComponent
import com.yet.bitmessage.shared.resources.Res
import com.yet.bitmessage.shared.resources.chat_back
import com.yet.bitmessage.shared.resources.chat_empty
import com.yet.bitmessage.shared.resources.chat_encrypted
import com.yet.bitmessage.shared.resources.chat_verified
import com.yet.bitmessage.shared.resources.chat_input_hint
import com.yet.bitmessage.shared.resources.chat_reach_internet
import com.yet.bitmessage.shared.resources.chat_reach_nearby
import com.yet.bitmessage.shared.resources.chat_reach_offline
import com.yet.bitmessage.shared.resources.chat_send
import com.yet.bitmessage.ui.component.button.IconCircleButton
import com.yet.bitmessage.ui.component.icon.ArrowBack
import com.yet.bitmessage.ui.component.icon.Check
import com.yet.bitmessage.ui.component.icon.Close
import com.yet.bitmessage.ui.component.icon.Done
import com.yet.bitmessage.ui.component.icon.DoneAll
import com.yet.bitmessage.ui.component.icon.Lock
import com.yet.bitmessage.ui.component.icon.Send
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatContent(component: ChatComponent, modifier: Modifier = Modifier) {
    val model by component.model.subscribeAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { ChatTitle(model) },
                navigationIcon = {
                    IconCircleButton(
                        icon = ArrowBack,
                        contentDescription = stringResource(Res.string.chat_back),
                        onClick = component::onBackClicked,
                    )
                },
            )
        },
        bottomBar = {
            MessageInput(
                draft = model.draft,
                canSend = model.canSend,
                onDraftChanged = component::onDraftChanged,
                onSendClicked = component::onSendClicked,
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                model.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                model.messages.isEmpty() -> Text(
                    text = stringResource(Res.string.chat_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> MessageTimeline(messages = model.messages)
            }
        }
    }
}

@Composable
private fun ChatTitle(model: ChatComponent.Model) {
    Column {
        Text(text = model.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (model.isEncrypted) {
                Icon(
                    imageVector = Lock,
                    contentDescription = stringResource(Res.string.chat_encrypted),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(12.dp),
                )
            }
            if (model.isVerified) {
                Icon(
                    imageVector = Done,
                    contentDescription = stringResource(Res.string.chat_verified),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(
                text = stringResource(model.reachability.label()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Reachability.label() = when (this) {
    Reachability.NEARBY -> Res.string.chat_reach_nearby
    Reachability.INTERNET -> Res.string.chat_reach_internet
    Reachability.OFFLINE -> Res.string.chat_reach_offline
}

@Composable
private fun MessageTimeline(messages: List<BitMessage>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    // Keep the newest message in view as the timeline grows.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            MessageBubble(message)
        }
    }
}

@Composable
private fun MessageBubble(message: BitMessage, modifier: Modifier = Modifier) {
    val alignment = if (message.isMine) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor =
        if (message.isMine) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!message.isMine && !message.isSystem) {
                    Text(
                        text = message.sender.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(text = message.content, style = MaterialTheme.typography.bodyMedium)
                if (message.isMine && message.deliveryStatus != null) {
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        DeliveryStatusIndicator(message.deliveryStatus!!)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeliveryStatusIndicator(status: DeliveryStatus, modifier: Modifier = Modifier) {
    val (icon, tint) = status.glyph()
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(14.dp),
    )
}

/** Maps a delivery status to a check-mark glyph + tint (mirrors the iOS check-mark ladder). */
@Composable
internal fun DeliveryStatus.glyph(): Pair<ImageVector, Color> = when (this) {
    DeliveryStatus.Sending -> Check to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    DeliveryStatus.Sent -> Check to MaterialTheme.colorScheme.onSurfaceVariant
    is DeliveryStatus.Delivered -> DoneAll to MaterialTheme.colorScheme.onSurfaceVariant
    is DeliveryStatus.PartiallyDelivered -> DoneAll to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    is DeliveryStatus.Read -> DoneAll to MaterialTheme.colorScheme.primary
    is DeliveryStatus.Failed -> Close to MaterialTheme.colorScheme.error
}

@Composable
private fun MessageInput(
    draft: String,
    canSend: Boolean,
    onDraftChanged: (String) -> Unit,
    onSendClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChanged,
                modifier = Modifier.weight(1f),
                placeholder = { Text(text = stringResource(Res.string.chat_input_hint)) },
                maxLines = 4,
            )
            IconCircleButton(
                icon = Send,
                contentDescription = stringResource(Res.string.chat_send),
                onClick = onSendClicked,
                enabled = canSend,
            )
        }
    }
}
