package com.yet.bitmessage.ui.screen.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.app.domain.model.GroupInfo
import com.app.domain.model.Peer
import com.app.domain.model.Reachability
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yet.bitmessage.feature.chats.conversations.groups.GroupsComponent
import com.yet.bitmessage.feature.chats.conversations.groups.list.GroupDialog
import com.yet.bitmessage.feature.chats.conversations.groups.list.GroupListComponent
import com.yet.bitmessage.shared.resources.Res
import com.yet.bitmessage.shared.resources.groups_cancel
import com.yet.bitmessage.shared.resources.groups_close
import com.yet.bitmessage.shared.resources.groups_create
import com.yet.bitmessage.shared.resources.groups_create_title
import com.yet.bitmessage.shared.resources.groups_creator_badge
import com.yet.bitmessage.shared.resources.groups_empty
import com.yet.bitmessage.shared.resources.groups_invite
import com.yet.bitmessage.shared.resources.groups_invite_empty
import com.yet.bitmessage.shared.resources.groups_invite_title
import com.yet.bitmessage.shared.resources.groups_leave
import com.yet.bitmessage.shared.resources.groups_member_count
import com.yet.bitmessage.shared.resources.groups_name_hint
import com.yet.bitmessage.shared.resources.groups_ok
import com.yet.bitmessage.shared.resources.groups_title
import com.yet.bitmessage.ui.component.button.IconCircleButton
import com.yet.bitmessage.ui.component.icon.Add
import com.yet.bitmessage.ui.component.icon.Close
import com.yet.bitmessage.ui.component.icon.MoreVert
import org.jetbrains.compose.resources.stringResource

/**
 * Private groups (0x25). A self-contained master-detail flow: the group list, and one group's live
 * chat, rendered from the component's [GroupsComponent.stack].
 */
@Composable
fun GroupsContent(component: GroupsComponent, modifier: Modifier = Modifier) {
    Children(stack = component.stack, modifier = modifier.fillMaxSize()) {
        when (val child = it.instance) {
            is GroupsComponent.Child.List -> GroupListContent(child.component)
            is GroupsComponent.Child.Chat -> GroupChatContent(child.component)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupListContent(component: GroupListComponent) {
    val model by component.model.subscribeAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                navigationIcon = {
                    IconCircleButton(
                        icon = Close,
                        contentDescription = stringResource(Res.string.groups_close),
                        onClick = component::onCloseClicked,
                    )
                },
                title = { Text(text = stringResource(Res.string.groups_title)) },
                actions = {
                    IconCircleButton(
                        icon = Add,
                        contentDescription = stringResource(Res.string.groups_create),
                        onClick = component::onCreateClicked,
                    )
                },
            )

            Box(modifier = Modifier.fillMaxSize()) {
                if (!model.isLoading && model.groups.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.groups_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(model.groups, key = { it.idHex }) { group ->
                            GroupRow(
                                group = group,
                                onOpen = { component.onGroupClicked(group.idHex, group.name) },
                                onInvite = { component.onInviteClicked(group.idHex) },
                                onLeave = { component.onLeave(group.idHex) },
                            )
                        }
                    }
                }
            }
        }
    }

    val dialog by component.dialog.subscribeAsState()
    when (val child = dialog.child?.instance) {
        GroupDialog.Create -> CreateGroupDialog(
            onConfirm = component::onSubmitCreate,
            onDismiss = component::onDismissDialog,
        )
        is GroupDialog.Invite -> InviteDialog(
            peers = child.peers,
            onSelect = { peer -> component.onSubmitInvite(child.groupIdHex, peer.id.raw) },
            onDismiss = component::onDismissDialog,
        )
        null -> Unit
    }
}

@Composable
private fun GroupRow(
    group: GroupInfo,
    onOpen: () -> Unit,
    onInvite: () -> Unit,
    onLeave: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        ListItem(
            leadingContent = { ConversationAvatar(title = group.name, reachability = Reachability.OFFLINE) },
            headlineContent = { Text(text = group.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(text = stringResource(Res.string.groups_member_count, group.memberCount, group.epoch))
            },
            trailingContent = {
                if (group.isCreator) {
                    Text(
                        text = stringResource(Res.string.groups_creator_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                IconCircleButton(
                    icon = MoreVert,
                    contentDescription = stringResource(Res.string.groups_leave),
                    onClick = { menuOpen = true },
                )
            },
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (group.isCreator) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.groups_invite)) },
                    onClick = { menuOpen = false; onInvite() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.groups_leave)) },
                onClick = { menuOpen = false; onLeave() },
            )
        }
    }
}

@Composable
private fun CreateGroupDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.groups_create_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.groups_name_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) {
                Text(stringResource(Res.string.groups_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.groups_cancel)) }
        },
    )
}

@Composable
private fun InviteDialog(peers: List<Peer>, onSelect: (Peer) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.groups_invite_title)) },
        text = {
            if (peers.isEmpty()) {
                Text(stringResource(Res.string.groups_invite_empty))
            } else {
                Column {
                    peers.forEach { peer ->
                        ListItem(
                            leadingContent = {
                                Icon(imageVector = Add, contentDescription = null)
                            },
                            headlineContent = { Text(peer.nickname.ifBlank { peer.id.raw }) },
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(peer) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.groups_ok)) }
        },
    )
}
