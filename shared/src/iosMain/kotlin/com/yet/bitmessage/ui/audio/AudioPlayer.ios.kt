package com.yet.bitmessage.ui.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * iOS voice-note playback (AVAudioPlayer) is not implemented yet — this stub keeps the shared UI
 * compiling and rendering; tapping a voice note does nothing. Follow-up alongside the other iOS
 * media actuals.
 */
@Composable
actual fun rememberAudioPlayerController(): AudioPlayerController =
    remember { StubAudioPlayerController }

private object StubAudioPlayerController : AudioPlayerController {
    override val playingPath: State<String?> = mutableStateOf(null)
    override fun toggle(path: String) = Unit
}
