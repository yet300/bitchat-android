package com.yet.bitmessage.ui.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * iOS voice playback (AudioConverter AAC-LC decode + AVAudioEngine) is not implemented yet — this stub
 * keeps the shared UI compiling; inbound bursts are dropped. Follow-up alongside the other iOS media
 * actuals (see [rememberVoiceCaptureController]).
 */
@Composable
actual fun rememberVoicePlayer(): VoicePlayer = remember { StubVoicePlayer }

private object StubVoicePlayer : VoicePlayer {
    override fun play(frames: List<ByteArray>) = Unit
}
