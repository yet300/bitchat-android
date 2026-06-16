package com.yet.bitmessage.ui.screen.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.domain.model.Reachability
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yet.bitmessage.feature.chats.conversations.contacts.ContactsComponent
import com.yet.bitmessage.shared.resources.Res
import com.yet.bitmessage.shared.resources.contacts_block
import com.yet.bitmessage.shared.resources.contacts_close
import com.yet.bitmessage.shared.resources.contacts_empty
import com.yet.bitmessage.shared.resources.contacts_mutual
import com.yet.bitmessage.shared.resources.contacts_section_blocked
import com.yet.bitmessage.shared.resources.contacts_section_favorites
import com.yet.bitmessage.shared.resources.contacts_title
import com.yet.bitmessage.shared.resources.contacts_unblock
import com.yet.bitmessage.shared.resources.contacts_verified
import com.yet.bitmessage.ui.component.button.IconCircleButton
import com.yet.bitmessage.ui.component.icon.Close
import com.yet.bitmessage.ui.component.icon.Done
import org.jetbrains.compose.resources.stringResource

/**
 * Contacts screen (D4): favorites and a blocked list. Tapping a contact opens a DM (handed to the
 * parent for navigation); the trailing button blocks / unblocks. Verifying (QR) is a follow-up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsContent(component: ContactsComponent, modifier: Modifier = Modifier) {
    val model by component.model.subscribeAsState()

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                navigationIcon = {
                    IconCircleButton(
                        icon = Close,
                        contentDescription = stringResource(Res.string.contacts_close),
                        onClick = component::onCloseClicked,
                    )
                },
                title = { Text(text = stringResource(Res.string.contacts_title)) },
            )

            val empty = !model.isLoading && model.favorites.isEmpty() && model.blocked.isEmpty()
            // Resolved here (composable scope): the LazyListScope builder below is not @Composable.
            val favoritesTitle = stringResource(Res.string.contacts_section_favorites)
            val blockedTitle = stringResource(Res.string.contacts_section_blocked)
            when {
                model.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                empty -> Text(
                    text = stringResource(Res.string.contacts_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 64.dp)) {
                    section(favoritesTitle, model.favorites, component)
                    section(blockedTitle, model.blocked, component)
                }
            }
        }
    }
}

private fun LazyListScope.section(
    title: String,
    rows: List<ContactsComponent.ContactRow>,
    component: ContactsComponent,
) {
    if (rows.isEmpty()) return
    item(key = "section-$title") {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        )
    }
    items(rows, key = { it.noiseKeyHex }) { row ->
        ContactRow(row, component)
    }
}

@Composable
private fun ContactRow(row: ContactsComponent.ContactRow, component: ContactsComponent) {
    ListItem(
        leadingContent = {
            ConversationAvatar(
                title = row.name,
                reachability = if (row.isMutual) Reachability.INTERNET else Reachability.OFFLINE,
            )
        },
        headlineContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = row.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.isVerified) {
                    Icon(
                        imageVector = Done,
                        contentDescription = stringResource(Res.string.contacts_verified),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        supportingContent = if (row.isMutual) {
            { Text(text = stringResource(Res.string.contacts_mutual)) }
        } else {
            null
        },
        trailingContent = {
            TextButton(onClick = { component.onToggleBlock(row.noiseKeyHex, !row.isBlocked) }) {
                Text(
                    text = stringResource(
                        if (row.isBlocked) Res.string.contacts_unblock else Res.string.contacts_block,
                    ),
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { component.onContactClicked(row.noiseKeyHex) },
    )
}
