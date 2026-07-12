package com.yet.bitmessage.ui.screen.chat

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yet.bitmessage.feature.chats.conversations.voice.VoiceComponent
import com.yet.bitmessage.shared.resources.Res
import com.yet.bitmessage.shared.resources.voice_close
import com.yet.bitmessage.shared.resources.voice_empty
import com.yet.bitmessage.shared.resources.voice_hold_to_talk
import com.yet.bitmessage.shared.resources.voice_received
import com.yet.bitmessage.shared.resources.voice_recording
import com.yet.bitmessage.shared.resources.voice_title
import com.yet.bitmessage.ui.component.button.IconCircleButton
import com.yet.bitmessage.ui.component.icon.Close
import com.yet.bitmessage.ui.component.icon.Mic
import com.yet.bitmessage.ui.voice.rememberVoiceCaptureController
import com.yet.bitmessage.ui.voice.rememberVoicePlayer
import org.jetbrains.compose.resources.stringResource

/**
 * Live public voice (0x29): push-to-talk broadcast plus playback of inbound bursts. Capture/encode
 * and decode/playback are platform controllers; this screen just wires them to the component.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceContent(component: VoiceComponent, modifier: Modifier = Modifier) {
    val model by component.model.subscribeAsState()
    val player = rememberVoicePlayer()
    val capture = rememberVoiceCaptureController(onRequestPermission = component::requestMicrophonePermission)
    val isRecording by capture.isRecording

    androidx.compose.runtime.LaunchedEffect(component) {
        component.playback.collect { frames -> player.play(frames) }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                navigationIcon = {
                    IconCircleButton(
                        icon = Close,
                        contentDescription = stringResource(Res.string.voice_close),
                        onClick = component::onCloseClicked,
                    )
                },
                title = { Text(text = stringResource(Res.string.voice_title)) },
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (model.received.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.voice_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(model.received) { burst ->
                            ListItem(
                                headlineContent = {
                                    Text(stringResource(Res.string.voice_received, burst.peerId, burst.durationMs))
                                },
                            )
                        }
                    }
                }
            }

            PushToTalkButton(
                isRecording = isRecording,
                onStart = capture::start,
                onStop = {
                    val captured = capture.stop()
                    if (captured != null) component.onBurstCaptured(captured.frames, captured.durationMs)
                },
            )
        }
    }
}

@Composable
private fun PushToTalkButton(isRecording: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(24.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            onStart()
                            tryAwaitRelease()
                            onStop()
                        },
                    )
                },
        ) {
            Surface(color = color, shape = CircleShape, modifier = Modifier.fillMaxSize()) {}
            Icon(
                imageVector = Mic,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            text = stringResource(if (isRecording) Res.string.voice_recording else Res.string.voice_hold_to_talk),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
