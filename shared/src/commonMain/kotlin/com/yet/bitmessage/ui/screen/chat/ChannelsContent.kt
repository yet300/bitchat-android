package com.yet.bitmessage.ui.screen.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.app.domain.model.Channel
import com.app.domain.model.Reachability
import com.app.domain.model.RetentionPolicy
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yet.bitmessage.feature.chats.conversations.channels.ChannelDialog
import com.yet.bitmessage.feature.chats.conversations.channels.ChannelsComponent
import com.yet.bitmessage.shared.resources.Res
import com.yet.bitmessage.shared.resources.channels_cancel
import com.yet.bitmessage.shared.resources.channels_change_password
import com.yet.bitmessage.shared.resources.channels_close
import com.yet.bitmessage.shared.resources.channels_empty
import com.yet.bitmessage.shared.resources.channels_join
import com.yet.bitmessage.shared.resources.channels_join_hint
import com.yet.bitmessage.shared.resources.channels_leave
import com.yet.bitmessage.shared.resources.channels_ok
import com.yet.bitmessage.shared.resources.channels_retention
import com.yet.bitmessage.shared.resources.retention_day
import com.yet.bitmessage.shared.resources.retention_keep_all
import com.yet.bitmessage.shared.resources.retention_month
import com.yet.bitmessage.shared.resources.retention_week
import com.yet.bitmessage.shared.resources.channels_password_hint
import com.yet.bitmessage.shared.resources.channels_password_title
import com.yet.bitmessage.shared.resources.channels_protected
import com.yet.bitmessage.shared.resources.channels_set_password
import com.yet.bitmessage.shared.resources.channels_title
import com.yet.bitmessage.ui.component.button.IconCircleButton
import com.yet.bitmessage.ui.component.icon.Close
import com.yet.bitmessage.ui.component.icon.MoreVert
import org.jetbrains.compose.resources.stringResource

/**
 * Channel management (D6): join (password-aware), list joined channels, leave, set/change password
 * and per-channel retention. Dialogs come from the component's Decompose ChildSlot. Tapping a
 * channel opens its chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsContent(component: ChannelsComponent, modifier: Modifier = Modifier) {
    val model by component.model.subscribeAsState()
    var joinInput by remember { mutableStateOf("") }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                navigationIcon = {
                    IconCircleButton(
                        icon = Close,
                        contentDescription = stringResource(Res.string.channels_close),
                        onClick = component::onCloseClicked,
                    )
                },
                title = { Text(text = stringResource(Res.string.channels_title)) },
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                OutlinedTextField(
                    value = joinInput,
                    onValueChange = { joinInput = it },
                    label = { Text(stringResource(Res.string.channels_join_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = joinInput.isNotBlank(),
                    onClick = {
                        component.onJoin(joinInput.trim())
                        joinInput = ""
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(stringResource(Res.string.channels_join))
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (!model.isLoading && model.channels.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.channels_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(model.channels, key = { it.tag }) { channel ->
                            ChannelRow(
                                channel = channel,
                                onOpen = { component.onChannelClicked(channel.tag) },
                                onLeave = { component.onLeave(channel.tag) },
                                onSetPassword = { component.onSetPasswordClicked(channel.tag) },
                                onRetention = { component.onRetentionClicked(channel.tag) },
                            )
                        }
                    }
                }
            }
        }
    }

    val dialog by component.dialog.subscribeAsState()
    when (val child = dialog.child?.instance) {
        is ChannelDialog.Password -> PasswordDialog(
            onConfirm = { component.onSubmitPassword(child.tag, child.mode, it) },
            onDismiss = component::onDismissDialog,
        )
        is ChannelDialog.Retention -> RetentionDialog(
            current = child.current,
            onSelect = { component.onRetentionSelected(child.tag, it) },
            onDismiss = component::onDismissDialog,
        )
        null -> Unit
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    onOpen: () -> Unit,
    onLeave: () -> Unit,
    onSetPassword: () -> Unit,
    onRetention: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        ListItem(
            leadingContent = { ConversationAvatar(title = channel.tag, reachability = Reachability.OFFLINE) },
            headlineContent = { Text(text = channel.tag, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = if (channel.isPasswordProtected) {
                { Text(text = stringResource(Res.string.channels_protected)) }
            } else {
                null
            },
            trailingContent = {
                IconCircleButton(
                    icon = MoreVert,
                    contentDescription = stringResource(Res.string.channels_set_password),
                    onClick = { menuOpen = true },
                )
            },
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (channel.isPasswordProtected) Res.string.channels_change_password
                            else Res.string.channels_set_password,
                        ),
                    )
                },
                onClick = { menuOpen = false; onSetPassword() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.channels_retention)) },
                onClick = { menuOpen = false; onRetention() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.channels_leave)) },
                onClick = { menuOpen = false; onLeave() },
            )
        }
    }
}

@Composable
private fun RetentionDialog(
    current: RetentionPolicy,
    onSelect: (RetentionPolicy) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.channels_retention)) },
        text = {
            Column {
                RetentionPolicy.entries.forEach { policy ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(policy) }
                            .padding(vertical = 8.dp),
                    ) {
                        RadioButton(selected = policy == current, onClick = { onSelect(policy) })
                        Text(text = stringResource(policy.label()), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.channels_ok)) }
        },
    )
}

private fun RetentionPolicy.label() = when (this) {
    RetentionPolicy.KEEP_ALL -> Res.string.retention_keep_all
    RetentionPolicy.DAY -> Res.string.retention_day
    RetentionPolicy.WEEK -> Res.string.retention_week
    RetentionPolicy.MONTH -> Res.string.retention_month
}

@Composable
private fun PasswordDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.channels_password_title)) },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(Res.string.channels_password_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = password.isNotBlank(), onClick = { onConfirm(password) }) {
                Text(stringResource(Res.string.channels_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.channels_cancel)) }
        },
    )
}
