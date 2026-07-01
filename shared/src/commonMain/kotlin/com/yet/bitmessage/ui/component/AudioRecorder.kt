package com.yet.bitmessage.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State

/**
 * Screen-scoped controller for one voice recording session. Recording is platform-inherent
 * (Android MediaRecorder → m4a), so this is an `expect`/`actual`.
 *
 * Call [start] when the user presses the mic; it first runs [onRequestPermission] (the microphone
 * runtime permission, owned by the feature component over the app's `PermissionController`) and only
 * records once granted. Call [stop] when the user releases; returns the recorded file path or null.
 */
@Stable
interface AudioRecorderController {
    val isRecording: State<Boolean>
    fun start()
    fun stop(): String?
}

/**
 * @param onRequestPermission requests the microphone permission (via the component's
 *   `PermissionController`) and returns whether it is granted. Kept as a suspend lambda so no platform
 *   permission types leak into the recorder.
 */
@Composable
expect fun rememberAudioRecorderController(
    onRequestPermission: suspend () -> Boolean,
): AudioRecorderController
