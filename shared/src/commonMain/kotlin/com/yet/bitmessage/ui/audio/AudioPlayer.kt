package com.yet.bitmessage.ui.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf

/**
 * Screen-scoped player for one voice/audio attachment at a time. Playback is platform-inherent
 * (Android MediaPlayer), so this is an `expect`/`actual`. Toggling the path already playing stops
 * it; toggling a different path switches to it. Exposed to the UI via [LocalAudioPlayer].
 */
@Stable
interface AudioPlayerController {
    /** File path currently playing, or null when idle. */
    val playingPath: State<String?>

    fun toggle(path: String)
}

@Composable
expect fun rememberAudioPlayerController(): AudioPlayerController

/** Defaults to a no-op so previews and non-chat surfaces render without owning a player. */
val LocalAudioPlayer = compositionLocalOf<AudioPlayerController> { NoOpAudioPlayerController }

private object NoOpAudioPlayerController : AudioPlayerController {
    override val playingPath: State<String?> = mutableStateOf(null)
    override fun toggle(path: String) = Unit
}
