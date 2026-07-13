package com.yet.bitmessage.ui.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State

/** The encoded result of one push-to-talk capture: AAC-LC (16 kHz mono) frames plus its duration. */
data class VoiceCapture(val frames: List<ByteArray>, val durationMs: Int)

/**
 * Screen-scoped push-to-talk controller. Capture + AAC-LC encoding is platform-inherent (Android
 * AudioRecord + MediaCodec), so this is an `expect`/`actual`. [start] runs [onRequestPermission]
 * first and only records once granted; [stop] returns the encoded frames, or null if nothing usable
 * was captured.
 */
@Stable
interface VoiceCaptureController {
    val isRecording: State<Boolean>
    fun start()
    fun stop(): VoiceCapture?
}

@Composable
expect fun rememberVoiceCaptureController(
    onRequestPermission: suspend () -> Boolean,
): VoiceCaptureController
