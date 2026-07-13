package com.yet.bitmessage.ui.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable

/**
 * Screen-scoped player for inbound live-voice bursts. Decoding AAC-LC (16 kHz mono) frames to PCM and
 * playing them is platform-inherent (Android MediaCodec + AudioTrack), so this is an `expect`/`actual`.
 * Bursts are ephemeral — [play] just renders the audio and keeps nothing.
 */
@Stable
interface VoicePlayer {
    /** Decodes and plays one burst's ordered AAC-LC frames. */
    fun play(frames: List<ByteArray>)
}

@Composable
expect fun rememberVoicePlayer(): VoicePlayer
