package com.yet.bitmessage.ui.audio

import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.app.common.utils.Log

@Composable
actual fun rememberAudioPlayerController(): AudioPlayerController {
    val controller = remember { AndroidAudioPlayerController() }
    DisposableEffect(controller) { onDispose { controller.release() } }
    return controller
}

private class AndroidAudioPlayerController : AudioPlayerController {
    private val _playingPath = mutableStateOf<String?>(null)
    override val playingPath: State<String?> = _playingPath
    private var player: MediaPlayer? = null

    override fun toggle(path: String) {
        if (_playingPath.value == path) {
            stop()
            return
        }
        stop()
        player = runCatching {
            MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
        }.onFailure { Log.e(TAG, "audio playback failed: $path", it) }.getOrNull()
        if (player != null) _playingPath.value = path
    }

    private fun stop() {
        player?.let { runCatching { it.release() } }
        player = null
        _playingPath.value = null
    }

    fun release() = stop()

    private companion object {
        const val TAG = "AudioPlayer"
    }
}
