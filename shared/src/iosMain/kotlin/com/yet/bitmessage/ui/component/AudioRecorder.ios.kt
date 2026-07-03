package com.yet.bitmessage.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * iOS voice recording (AVAudioRecorder) is not implemented yet — this stub keeps the shared UI
 * compiling; the mic button records nothing and [AudioRecorderController.stop] returns null (the
 * composer treats that as "no recording"). Follow-up alongside the other iOS media actuals.
 */
@Composable
actual fun rememberAudioRecorderController(
    onRequestPermission: suspend () -> Boolean,
): AudioRecorderController = remember { StubAudioRecorderController }

private object StubAudioRecorderController : AudioRecorderController {
    override val isRecording: State<Boolean> = mutableStateOf(false)
    override fun start() = Unit
    override fun stop(): String? = null
}
