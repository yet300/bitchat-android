package com.yet.bitmessage.ui.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * iOS voice capture (AVAudioEngine + AudioConverter AAC-LC) is not implemented yet — this stub keeps
 * the shared UI compiling; the PTT button captures nothing and [stop] returns null. Follow-up
 * alongside the other iOS media actuals (see [rememberVoicePlayer]).
 */
@Composable
actual fun rememberVoiceCaptureController(
    onRequestPermission: suspend () -> Boolean,
): VoiceCaptureController = remember { StubVoiceCaptureController }

private object StubVoiceCaptureController : VoiceCaptureController {
    override val isRecording: State<Boolean> = mutableStateOf(false)
    override fun start() = Unit
    override fun stop(): VoiceCapture? = null
}
