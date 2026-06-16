package com.yet.bitmessage.ui.screen.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import com.app.domain.model.ThemeMode
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yet.bitmessage.feature.chats.conversations.settings.SettingsComponent
import com.yet.bitmessage.feature.chats.conversations.settings.SettingsDialog
import com.yet.bitmessage.shared.resources.Res
import com.yet.bitmessage.shared.resources.settings_cancel
import com.yet.bitmessage.shared.resources.settings_close
import com.yet.bitmessage.shared.resources.settings_fingerprint
import com.yet.bitmessage.shared.resources.settings_nickname
import com.yet.bitmessage.shared.resources.settings_npub
import com.yet.bitmessage.shared.resources.settings_my_qr_close
import com.yet.bitmessage.shared.resources.settings_my_qr_desc
import com.yet.bitmessage.shared.resources.settings_my_qr_title
import com.yet.bitmessage.shared.resources.settings_my_qr_unavailable
import com.yet.bitmessage.shared.resources.settings_show_my_qr
import com.yet.bitmessage.shared.resources.settings_panic
import com.yet.bitmessage.shared.resources.settings_panic_confirm
import com.yet.bitmessage.shared.resources.settings_panic_confirm_body
import com.yet.bitmessage.shared.resources.settings_panic_confirm_title
import com.yet.bitmessage.shared.resources.settings_panic_desc
import com.yet.bitmessage.shared.resources.settings_auto_start
import com.yet.bitmessage.shared.resources.settings_notif_enable
import com.yet.bitmessage.shared.resources.settings_notif_mute_all
import com.yet.bitmessage.shared.resources.settings_notif_permission_label
import com.yet.bitmessage.shared.resources.settings_notif_permission_on
import com.yet.bitmessage.shared.resources.settings_pow
import com.yet.bitmessage.shared.resources.settings_pow_difficulty
import com.yet.bitmessage.shared.resources.settings_run_background
import com.yet.bitmessage.shared.resources.settings_section_appearance
import com.yet.bitmessage.shared.resources.settings_section_background
import com.yet.bitmessage.shared.resources.settings_section_danger
import com.yet.bitmessage.shared.resources.settings_section_identity
import com.yet.bitmessage.shared.resources.settings_section_network
import com.yet.bitmessage.shared.resources.settings_section_notifications
import com.yet.bitmessage.shared.resources.settings_theme
import com.yet.bitmessage.shared.resources.settings_theme_dark
import com.yet.bitmessage.shared.resources.settings_theme_light
import com.yet.bitmessage.shared.resources.settings_theme_system
import com.yet.bitmessage.shared.resources.settings_title
import com.yet.bitmessage.shared.resources.settings_tor
import com.yet.bitmessage.ui.component.QrCodeImage
import com.yet.bitmessage.ui.component.button.IconCircleButton
import com.yet.bitmessage.ui.component.icon.Close
import com.yet.bitmessage.feature.chats.conversations.settings.NotifPermissionStatus
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
    val dialog by component.dialog.subscribeAsState()

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
                TextButton(
                    onClick = component::onShowMyQrClicked,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(stringResource(Res.string.settings_show_my_qr))
                }

                SectionHeader(stringResource(Res.string.settings_section_appearance))
                ThemeSelector(model.theme, component::onThemeSelected)

                SectionHeader(stringResource(Res.string.settings_section_network))
                ToggleRow(stringResource(Res.string.settings_tor), model.torEnabled, component::onTorToggled)
                ToggleRow(stringResource(Res.string.settings_pow), model.powEnabled, component::onPowToggled)
                if (model.powEnabled && model.powLevels.isNotEmpty()) {
                    Text(
                        text = stringResource(Res.string.settings_pow_difficulty),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        model.powLevels.forEach { level ->
                            FilterChip(
                                selected = level.difficulty == model.powDifficulty,
                                onClick = { component.onPowDifficultySelected(level.difficulty) },
                                label = { Text(level.label) },
                            )
                        }
                    }
                }

                SectionHeader(stringResource(Res.string.settings_section_background))
                ToggleRow(stringResource(Res.string.settings_auto_start), model.autoStartEnabled, component::onAutoStartToggled)
                ToggleRow(stringResource(Res.string.settings_run_background), model.backgroundEnabled, component::onBackgroundToggled)

                SectionHeader(stringResource(Res.string.settings_section_notifications))
                NotifPermissionRow(model.notifPermission, component::onEnableNotificationsClicked)
                ToggleRow(stringResource(Res.string.settings_notif_mute_all), model.globalMuteEnabled, component::onGlobalMuteToggled)

                SectionHeader(stringResource(Res.string.settings_section_danger))
                Text(
                    text = stringResource(Res.string.settings_panic_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Button(
                    onClick = component::onPanicWipeClicked,
                    enabled = !model.isWiping,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                ) {
                    Text(text = stringResource(Res.string.settings_panic))
                }
            }
        }
    }

    when (dialog.child?.instance) {
        is SettingsDialog.PanicConfirm -> AlertDialog(
            onDismissRequest = component::onDismissDialog,
            title = { Text(stringResource(Res.string.settings_panic_confirm_title)) },
            text = { Text(stringResource(Res.string.settings_panic_confirm_body)) },
            confirmButton = {
                TextButton(onClick = component::onConfirmPanicWipe) {
                    Text(
                        text = stringResource(Res.string.settings_panic_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = component::onDismissDialog) {
                    Text(stringResource(Res.string.settings_cancel))
                }
            },
        )
        is SettingsDialog.MyQr -> AlertDialog(
            onDismissRequest = component::onDismissDialog,
            title = { Text(stringResource(Res.string.settings_my_qr_title)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    val qr = model.myQr
                    if (qr != null) {
                        QrCodeImage(payload = qr)
                    } else {
                        Text(stringResource(Res.string.settings_my_qr_unavailable))
                    }
                    Text(
                        text = stringResource(Res.string.settings_my_qr_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = component::onDismissDialog) {
                    Text(stringResource(Res.string.settings_my_qr_close))
                }
            },
        )
        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSelector(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val options = listOf(
        ThemeMode.SYSTEM to stringResource(Res.string.settings_theme_system),
        ThemeMode.LIGHT to stringResource(Res.string.settings_theme_light),
        ThemeMode.DARK to stringResource(Res.string.settings_theme_dark),
    )
    Text(
        text = stringResource(Res.string.settings_theme),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        options.forEach { (mode, label) ->
            FilterChip(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(text = label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onToggle)
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

@Composable
private fun NotifPermissionRow(status: NotifPermissionStatus, onEnable: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings_notif_permission_label),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        when (status) {
            NotifPermissionStatus.LOADING -> Unit
            NotifPermissionStatus.GRANTED ->
                Text(
                    text = stringResource(Res.string.settings_notif_permission_on),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            NotifPermissionStatus.DENIED ->
                TextButton(onClick = onEnable) {
                    Text(stringResource(Res.string.settings_notif_enable))
                }
        }
    }
}
