package com.yet.bitmessage.ui.screen.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.domain.model.BoardPost
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yet.bitmessage.feature.chats.conversations.boards.BoardDialog
import com.yet.bitmessage.feature.chats.conversations.boards.BoardsComponent
import com.yet.bitmessage.shared.resources.Res
import com.yet.bitmessage.shared.resources.boards_cancel
import com.yet.bitmessage.shared.resources.boards_close
import com.yet.bitmessage.shared.resources.boards_content_hint
import com.yet.bitmessage.shared.resources.boards_create
import com.yet.bitmessage.shared.resources.boards_create_title
import com.yet.bitmessage.shared.resources.boards_delete
import com.yet.bitmessage.shared.resources.boards_empty
import com.yet.bitmessage.shared.resources.boards_expiry_days
import com.yet.bitmessage.shared.resources.boards_geohash_hint
import com.yet.bitmessage.shared.resources.boards_ok
import com.yet.bitmessage.shared.resources.boards_post
import com.yet.bitmessage.shared.resources.boards_title
import com.yet.bitmessage.shared.resources.boards_urgent
import com.yet.bitmessage.ui.component.button.IconCircleButton
import com.yet.bitmessage.ui.component.icon.Add
import com.yet.bitmessage.ui.component.icon.Close
import org.jetbrains.compose.resources.stringResource

/**
 * Geohash bulletin boards (0x23): switch board by geohash, read posts (urgent first), create a post,
 * tombstone your own. The compose overlay is the component's Decompose ChildSlot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardsContent(component: BoardsComponent, modifier: Modifier = Modifier) {
    val model by component.model.subscribeAsState()
    var geohashInput by remember(model.geohash) { mutableStateOf(model.geohash) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                navigationIcon = {
                    IconCircleButton(
                        icon = Close,
                        contentDescription = stringResource(Res.string.boards_close),
                        onClick = component::onCloseClicked,
                    )
                },
                title = { Text(text = stringResource(Res.string.boards_title)) },
                actions = {
                    IconCircleButton(
                        icon = Add,
                        contentDescription = stringResource(Res.string.boards_create),
                        onClick = component::onCreateClicked,
                    )
                },
            )

            OutlinedTextField(
                value = geohashInput,
                onValueChange = { geohashInput = it },
                label = { Text(stringResource(Res.string.boards_geohash_hint)) },
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = { component.onSelectBoard(geohashInput) }) {
                        Text(stringResource(Res.string.boards_ok))
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Box(modifier = Modifier.fillMaxSize()) {
                if (!model.isLoading && model.posts.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.boards_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(model.posts, key = { it.idHex }) { post ->
                            BoardRow(post = post, onDelete = { component.onDelete(post.idHex) })
                        }
                    }
                }
            }
        }
    }

    val dialog by component.dialog.subscribeAsState()
    when (dialog.child?.instance) {
        BoardDialog.Create -> CreatePostDialog(
            onConfirm = component::onSubmitCreate,
            onDismiss = component::onDismissDialog,
        )
        null -> Unit
    }
}

@Composable
private fun BoardRow(post: BoardPost, onDelete: () -> Unit) {
    ListItem(
        overlineContent = if (post.isUrgent) {
            { Text(stringResource(Res.string.boards_urgent), color = MaterialTheme.colorScheme.error) }
        } else {
            null
        },
        headlineContent = { Text(post.content, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(post.authorNickname) },
        trailingContent = if (post.isMine) {
            {
                TextButton(onClick = onDelete) { Text(stringResource(Res.string.boards_delete)) }
            }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CreatePostDialog(onConfirm: (String, Boolean, Int) -> Unit, onDismiss: () -> Unit) {
    var content by remember { mutableStateOf("") }
    var urgent by remember { mutableStateOf(false) }
    var expiry by remember { mutableFloatStateOf(1f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.boards_create_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(Res.string.boards_content_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(stringResource(Res.string.boards_urgent))
                    Switch(checked = urgent, onCheckedChange = { urgent = it })
                }
                Text(stringResource(Res.string.boards_expiry_days, expiry.toInt()))
                Slider(value = expiry, onValueChange = { expiry = it }, valueRange = 1f..7f, steps = 5)
            }
        },
        confirmButton = {
            TextButton(
                enabled = content.isNotBlank(),
                onClick = { onConfirm(content.trim(), urgent, expiry.toInt()) },
            ) { Text(stringResource(Res.string.boards_post)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.boards_cancel)) }
        },
    )
}
