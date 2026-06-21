package com.yet.bitmessage.ui.screen.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import com.arkivanov.decompose.router.slot.ChildSlot
import coil3.compose.AsyncImage
import com.app.domain.model.Attachment
import com.app.domain.model.AttachmentKind
import com.app.domain.model.BitMessage
import com.app.domain.model.ConversationId
import com.yet.bitmessage.ui.audio.LocalAudioPlayer
import com.yet.bitmessage.ui.audio.rememberAudioPlayerController
import com.yet.bitmessage.ui.component.icon.PlayArrow
import com.yet.bitmessage.ui.component.icon.Stop
import com.app.domain.model.DeliveryStatus
import com.app.domain.model.GeoPerson
import com.app.domain.model.LocationNote
import com.app.domain.model.MessageType
import com.app.domain.model.Reachability
import com.app.domain.repository.VerifyScanResult
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yet.bitmessage.feature.chats.details.ChatComponent
import com.yet.bitmessage.feature.chats.details.notes.LocationNotesComponent
import com.yet.bitmessage.feature.chats.details.verify.VerifyScanComponent
import com.yet.bitmessage.shared.resources.Res
import com.yet.bitmessage.shared.resources.chat_attach
import com.yet.bitmessage.shared.resources.chat_back
import com.yet.bitmessage.shared.resources.chat_empty
import com.yet.bitmessage.shared.resources.chat_encrypted
import com.yet.bitmessage.shared.resources.chat_verified
import com.yet.bitmessage.shared.resources.chat_input_hint
import com.yet.bitmessage.shared.resources.chat_geo_here
import com.yet.bitmessage.shared.resources.chat_geo_participants
import com.yet.bitmessage.shared.resources.chat_geo_participants_empty
import com.yet.bitmessage.shared.resources.chat_geo_teleported
import com.yet.bitmessage.shared.resources.chat_reach_internet
import com.yet.bitmessage.shared.resources.chat_reach_nearby
import com.yet.bitmessage.shared.resources.chat_reach_offline
import com.yet.bitmessage.shared.resources.chat_send
import com.yet.bitmessage.shared.resources.media_audio
import com.yet.bitmessage.shared.resources.media_cancel
import com.yet.bitmessage.shared.resources.media_play
import com.yet.bitmessage.shared.resources.media_stop
import com.yet.bitmessage.shared.resources.media_file
import com.yet.bitmessage.shared.resources.media_image
import com.yet.bitmessage.shared.resources.notes_empty
import com.yet.bitmessage.shared.resources.notes_hint
import com.yet.bitmessage.shared.resources.notes_send
import com.yet.bitmessage.shared.resources.notes_title
import com.yet.bitmessage.shared.resources.verify_action
import com.yet.bitmessage.shared.resources.verify_camera_grant
import com.yet.bitmessage.shared.resources.verify_camera_needed
import com.yet.bitmessage.shared.resources.verify_close
import com.yet.bitmessage.shared.resources.verify_invalid
import com.yet.bitmessage.shared.resources.verify_peer_not_found
import com.yet.bitmessage.shared.resources.verify_scan_prompt
import com.yet.bitmessage.shared.resources.verify_scan_title
import com.yet.bitmessage.shared.resources.verify_started
import com.yet.bitmessage.ui.component.AudioRecorderController
import com.yet.bitmessage.ui.component.CameraScanner
import com.yet.bitmessage.ui.component.rememberAttachmentPicker
import com.yet.bitmessage.ui.component.rememberAudioRecorderController
import com.yet.bitmessage.ui.component.button.IconCircleButton
import com.yet.bitmessage.ui.component.icon.AccountCircle
import com.yet.bitmessage.ui.component.icon.Add
import com.yet.bitmessage.ui.component.icon.ArrowBack
import com.yet.bitmessage.ui.component.icon.Check
import com.yet.bitmessage.ui.component.icon.Close
import com.yet.bitmessage.ui.component.icon.Done
import com.yet.bitmessage.ui.component.icon.LocationOn
import com.yet.bitmessage.ui.component.icon.DoneAll
import com.yet.bitmessage.ui.component.icon.Lock
import com.yet.bitmessage.ui.component.icon.Mic
import com.yet.bitmessage.ui.component.icon.QrCodeScanner
import com.yet.bitmessage.ui.component.icon.Send
import com.yet.bitmessage.shared.resources.chat_record
import org.jetbrains.compose.resources.stringResource

@Composable
fun ChatContent(component: ChatComponent, modifier: Modifier = Modifier) {
    val model by component.model.subscribeAsState()
    val sheet by component.sheetSlot.subscribeAsState()
    val launchAttachmentPicker = rememberAttachmentPicker(onPicked = component::onAttachmentPicked)
    val audioRecorder = rememberAudioRecorderController()
    // Geo media has no Nostr file path yet (AttachmentSender drops it), so hide attach there.
    val canAttach = model.conversationId !is ConversationId.Geohash

    CompositionLocalProvider(LocalAudioPlayer provides rememberAudioPlayerController()) {
        ChatScaffold(component, model, sheet, launchAttachmentPicker, canAttach, audioRecorder, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScaffold(
    component: ChatComponent,
    model: ChatComponent.Model,
    sheet: ChildSlot<*, ChatComponent.ChatSheetChild>,
    launchAttachmentPicker: () -> Unit,
    canAttach: Boolean,
    audioRecorder: AudioRecorderController,
    modifier: Modifier = Modifier,
) {
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
                actions = {
                    // Per-contact QR verification is only meaningful for an unverified DM.
                    if (model.isEncrypted && !model.isVerified) {
                        IconButton(onClick = component::onVerifyClicked) {
                            Icon(
                                imageVector = QrCodeScanner,
                                contentDescription = stringResource(Res.string.verify_action),
                            )
                        }
                    }
                    // Geo chats: bookmark the room + see who's present in this geohash.
                    if (model.conversationId is ConversationId.Geohash) {
                        IconButton(onClick = component::onToggleBookmark) {
                            Text(
                                text = if (model.isBookmarked) "★" else "☆",
                                color = if (model.isBookmarked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = component::onParticipantsClicked) {
                            Icon(
                                imageVector = AccountCircle,
                                contentDescription = stringResource(Res.string.chat_geo_participants),
                            )
                        }
                        // Location notes are building-level (8-char geohash) only.
                        if ((model.conversationId as? ConversationId.Geohash)?.channel?.geohash?.length == 8) {
                            IconButton(onClick = component::onNotesClicked) {
                                Icon(
                                    imageVector = LocationOn,
                                    contentDescription = stringResource(Res.string.notes_title),
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                if (model.mentionSuggestions.isNotEmpty()) {
                    MentionSuggestions(
                        suggestions = model.mentionSuggestions,
                        onSelect = component::onMentionSelected,
                    )
                }
                MessageInput(
                    draft = model.draft,
                    canSend = model.canSend,
                    canAttach = canAttach,
                    onDraftChanged = component::onDraftChanged,
                    onSendClicked = component::onSendClicked,
                    onAttachClicked = launchAttachmentPicker,
                    audioRecorder = audioRecorder,
                    onVoiceRecorded = { path ->
                        component.onAttachmentPicked(
                            Attachment(
                                kind = AttachmentKind.AUDIO,
                                ref = path,
                                mime = "audio/mp4",
                            )
                        )
                    },
                )
            }
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

                else -> MessageTimeline(
                    messages = model.messages,
                    targetMessageId = model.targetMessageId,
                    onCancelAttachment = component::onCancelTransfer,
                )
            }
        }
    }

    when (val child = sheet.child?.instance) {
        is ChatComponent.ChatSheetChild.VerifyScan ->
            VerifyScanSheet(component = child.component, onDismiss = component::onDismissSheet)
        ChatComponent.ChatSheetChild.Participants ->
            GeoParticipantsSheet(
                participants = model.participants,
                onParticipantClick = component::onParticipantSelected,
                onDismiss = component::onDismissSheet,
            )
        is ChatComponent.ChatSheetChild.LocationNotes ->
            LocationNotesSheet(component = child.component, onDismiss = component::onDismissSheet)
        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeoParticipantsSheet(
    participants: List<GeoPerson>,
    onParticipantClick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(Res.string.chat_geo_participants),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (participants.isEmpty()) {
            Text(
                text = stringResource(Res.string.chat_geo_participants_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                items(participants, key = { it.pubkeyHex }) { person ->
                    GeoParticipantRow(person, onClick = { onParticipantClick(person.pubkeyHex) })
                }
            }
        }
    }
}

@Composable
private fun GeoParticipantRow(person: GeoPerson, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = AccountCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = person.displayName,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (person.isTeleported) {
            Icon(
                imageVector = LocationOn,
                contentDescription = stringResource(Res.string.chat_geo_teleported),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationNotesSheet(component: LocationNotesComponent, onDismiss: () -> Unit) {
    val model by component.model.subscribeAsState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(Res.string.notes_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp)) {
            when {
                model.isLoading && model.notes.isEmpty() ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                model.notes.isEmpty() -> Text(
                    text = stringResource(Res.string.notes_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(model.notes, key = { it.id }) { note -> LocationNoteRow(note) }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = model.draft,
                onValueChange = component::onDraftChanged,
                modifier = Modifier.weight(1f),
                placeholder = { Text(text = stringResource(Res.string.notes_hint)) },
                maxLines = 3,
            )
            IconCircleButton(
                icon = Send,
                contentDescription = stringResource(Res.string.notes_send),
                onClick = component::onSendClicked,
                enabled = model.canSend,
            )
        }
    }
}

@Composable
private fun LocationNoteRow(note: LocationNote) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(
            text = note.authorName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(text = note.content, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun VerifyScanSheet(component: VerifyScanComponent, onDismiss: () -> Unit) {
    val model by component.model.subscribeAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.verify_scan_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                when {
                    !model.cameraGranted -> {
                        Text(
                            text = stringResource(Res.string.verify_camera_needed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = component::onRequestCameraPermission,
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text(stringResource(Res.string.verify_camera_grant))
                        }
                    }

                    else -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .size(260.dp)
                                .clip(RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CameraScanner(onScanned = component::onQrScanned, modifier = Modifier.fillMaxSize())
                        }
                        val status = when (val r = model.result) {
                            is VerifyScanResult.Started -> stringResource(Res.string.verify_started, r.nickname)
                            VerifyScanResult.Invalid -> stringResource(Res.string.verify_invalid)
                            VerifyScanResult.PeerNotFound -> stringResource(Res.string.verify_peer_not_found)
                            null -> stringResource(Res.string.verify_scan_prompt)
                        }
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.verify_close)) }
        },
    )
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
            val subtitle = if (model.conversationId is ConversationId.Geohash) {
                stringResource(Res.string.chat_geo_here, model.participantCount)
            } else {
                stringResource(model.reachability.label())
            }
            Text(
                text = subtitle,
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
private fun MessageTimeline(
    messages: List<BitMessage>,
    targetMessageId: String?,
    onCancelAttachment: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // A search jump scrolls to the matched message once; otherwise keep the newest in view.
    var jumped by remember(targetMessageId) { mutableStateOf(false) }
    LaunchedEffect(messages.size, targetMessageId) {
        if (messages.isEmpty()) return@LaunchedEffect
        val targetIndex = targetMessageId
            ?.takeUnless { jumped }
            ?.let { id -> messages.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
        when {
            targetIndex != null -> {
                listState.animateScrollToItem(targetIndex)
                jumped = true
            }
            targetMessageId == null -> listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            MessageBubble(
                message,
                highlighted = message.id == targetMessageId,
                onCancelAttachment = onCancelAttachment,
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: BitMessage,
    onCancelAttachment: (String) -> Unit,
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val alignment = if (message.isMine) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor =
        if (message.isMine) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    val shape = RoundedCornerShape(12.dp)
    // A search-jump target gets a primary outline so the user spots it after the scroll.
    val highlight =
        if (highlighted) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            color = bubbleColor,
            shape = shape,
            modifier = Modifier.widthIn(max = 320.dp).then(highlight),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!message.isMine && !message.isSystem) {
                    Text(
                        text = message.sender.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (message.type == MessageType.TEXT) {
                    Text(
                        text = highlightMentions(message.content, MaterialTheme.colorScheme.primary),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    AttachmentContent(message, onCancel = { onCancelAttachment(message.id) })
                }
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
private fun AttachmentContent(message: BitMessage, onCancel: () -> Unit) {
    val label = message.attachment?.ref?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: when (message.type) {
            MessageType.IMAGE -> stringResource(Res.string.media_image)
            MessageType.AUDIO -> stringResource(Res.string.media_audio)
            else -> stringResource(Res.string.media_file)
        }
    val status = message.deliveryStatus
    val inProgress = message.isMine && status is DeliveryStatus.PartiallyDelivered && status.reached < status.total

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // For image messages the local file path lives in `content`; show it once the transfer lands.
        if (message.type == MessageType.IMAGE && !inProgress && message.content.isNotBlank()) {
            ImageThumbnail(message.content, label)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (message.type == MessageType.AUDIO && !inProgress && message.content.isNotBlank()) {
                AudioPlayButton(message.content)
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            message.attachment?.sizeBytes?.let { size ->
                Text(
                    text = formatSize(size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (inProgress) {
                IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Close,
                        contentDescription = stringResource(Res.string.media_cancel),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (inProgress) {
            val progress = status
            LinearProgressIndicator(
                progress = { if (progress.total > 0) (progress.reached.toFloat() / progress.total).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Play/stop toggle for a voice/audio attachment, driven by the screen-scoped [LocalAudioPlayer]. */
@Composable
private fun AudioPlayButton(path: String) {
    val player = LocalAudioPlayer.current
    val isPlaying = player.playingPath.value == path
    IconButton(onClick = { player.toggle(path) }, modifier = Modifier.size(28.dp)) {
        Icon(
            imageVector = if (isPlaying) Stop else PlayArrow,
            contentDescription = stringResource(if (isPlaying) Res.string.media_stop else Res.string.media_play),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Bounded, rounded inline preview of a received/sent image, loaded from its local file path. */
@Composable
private fun ImageThumbnail(path: String, contentDescription: String) {
    AsyncImage(
        model = "file://$path",
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .heightIn(max = 200.dp)
            .widthIn(max = 240.dp)
            .clip(RoundedCornerShape(12.dp)),
    )
}

private val MENTION_REGEX = Regex("@[a-zA-Z0-9_]+")

/** Styles @-mentions in a message body (display only; the known-mention set is computed on send). */
private fun highlightMentions(content: String, color: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (match in MENTION_REGEX.findAll(content)) {
        append(content.substring(last, match.range.first))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(match.value) }
        last = match.range.last + 1
    }
    append(content.substring(last))
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
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
private fun MentionSuggestions(suggestions: List<String>, onSelect: (String) -> Unit) {
    Surface(tonalElevation = 3.dp) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(suggestions, key = { it }) { nickname ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.clickable { onSelect(nickname) },
                ) {
                    Text(
                        text = "@$nickname",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageInput(
    draft: String,
    canSend: Boolean,
    canAttach: Boolean,
    onDraftChanged: (String) -> Unit,
    onSendClicked: () -> Unit,
    onAttachClicked: () -> Unit,
    audioRecorder: AudioRecorderController,
    onVoiceRecorded: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRecording by audioRecorder.isRecording
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (canAttach) {
                IconCircleButton(
                    icon = Add,
                    contentDescription = stringResource(Res.string.chat_attach),
                    onClick = onAttachClicked,
                )
            }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChanged,
                modifier = Modifier.weight(1f),
                placeholder = { Text(text = stringResource(Res.string.chat_input_hint)) },
                maxLines = 4,
            )
            if (!canSend) {
                IconCircleButton(
                    icon = Mic,
                    contentDescription = stringResource(Res.string.chat_record),
                    onClick = {
                        if (isRecording) {
                            audioRecorder.stop()?.let { onVoiceRecorded(it) }
                        } else {
                            audioRecorder.start()
                        }
                    },
                    enabled = true,
                )
            } else {
                IconCircleButton(
                    icon = Send,
                    contentDescription = stringResource(Res.string.chat_send),
                    onClick = onSendClicked,
                    enabled = canSend,
                )
            }
        }
    }
}
