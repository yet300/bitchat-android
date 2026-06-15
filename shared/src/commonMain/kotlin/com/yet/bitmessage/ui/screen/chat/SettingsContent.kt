package com.yet.bitmessage.ui.screen.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yet.bitmessage.feature.chats.conversations.settings.SettingsComponent
import com.yet.bitmessage.shared.resources.Res
import com.yet.bitmessage.shared.resources.settings_cancel
import com.yet.bitmessage.shared.resources.settings_close
import com.yet.bitmessage.shared.resources.settings_fingerprint
import com.yet.bitmessage.shared.resources.settings_nickname
import com.yet.bitmessage.shared.resources.settings_npub
import com.yet.bitmessage.shared.resources.settings_panic
import com.yet.bitmessage.shared.resources.settings_panic_confirm
import com.yet.bitmessage.shared.resources.settings_panic_confirm_body
import com.yet.bitmessage.shared.resources.settings_panic_confirm_title
import com.yet.bitmessage.shared.resources.settings_panic_desc
import com.yet.bitmessage.shared.resources.settings_section_danger
import com.yet.bitmessage.shared.resources.settings_section_identity
import com.yet.bitmessage.shared.resources.settings_title
import com.yet.bitmessage.ui.component.button.IconCircleButton
import com.yet.bitmessage.ui.component.icon.Close
import org.jetbrains.compose.resources.stringResource

/**
 * Settings screen (D5) — first slice: identity (editable nickname + read-only npub / fingerprint)
 * and the destructive panic wipe behind a confirmation dialog. Other sections (theme, Tor, PoW,
 * notifications, mesh background) arrive in a follow-up slice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(component: SettingsComponent, modifier: Modifier = Modifier) {
    val model by component.model.subscribeAsState()
    var confirmWipe by remember { mutableStateOf(false) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                navigationIcon = {
                    IconCircleButton(
                        icon = Close,
                        contentDescription = stringResource(Res.string.settings_close),
                        onClick = component::onCloseClicked,
                    )
                },
                title = { Text(text = stringResource(Res.string.settings_title)) },
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                SectionHeader(stringResource(Res.string.settings_section_identity))

                // Local edit buffer seeded from the persisted value; each edit persists immediately.
                var nickname by remember(model.nickname) { mutableStateOf(model.nickname) }
                OutlinedTextField(
                    value = nickname,
                    onValueChange = {
                        nickname = it
                        component.onNicknameChanged(it)
                    },
                    label = { Text(stringResource(Res.string.settings_nickname)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                model.npub?.let { ReadOnlyField(stringResource(Res.string.settings_npub), it) }
                ReadOnlyField(stringResource(Res.string.settings_fingerprint), model.fingerprint)

                SectionHeader(stringResource(Res.string.settings_section_danger))
                Text(
                    text = stringResource(Res.string.settings_panic_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Button(
                    onClick = { confirmWipe = true },
                    enabled = !model.isWiping,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                ) {
                    Text(text = stringResource(Res.string.settings_panic))
                }
            }
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text(stringResource(Res.string.settings_panic_confirm_title)) },
            text = { Text(stringResource(Res.string.settings_panic_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmWipe = false; component.onPanicWipe() }) {
                    Text(
                        text = stringResource(Res.string.settings_panic_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) {
                    Text(stringResource(Res.string.settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun ReadOnlyField(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
