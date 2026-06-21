package com.yet.bitmessage.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State

/**
 * Screen-scoped controller for one voice recording session. Recording is platform-inherent
 * (Android MediaRecorder → m4a), so this is an `expect`/`actual`.
 *
 * Call [start] when the user presses the mic (the actual requests RECORD_AUDIO if needed).
 * Call [stop] when the user releases; returns the recorded file path or null on error/denial.
 */
@Stable
interface AudioRecorderController {
    val isRecording: State<Boolean>
    fun start()
    fun stop(): String?
}

@Composable
expect fun rememberAudioRecorderController(): AudioRecorderController
