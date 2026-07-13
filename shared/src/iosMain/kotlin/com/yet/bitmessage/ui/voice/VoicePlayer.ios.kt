package com.yet.bitmessage.ui.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeSpokenAudio
import platform.AVFAudio.setActive

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberVoicePlayer(): VoicePlayer = remember { IosVoicePlayer() }

@OptIn(ExperimentalForeignApi::class)
private class IosVoicePlayer : VoicePlayer {
    private var engine: AVAudioEngine? = null
    private var node: AVAudioPlayerNode? = null

    override fun play(frames: List<ByteArray>) {
        if (frames.isEmpty()) return
        node?.stop()
        engine?.stop()

        val session = AVAudioSession.sharedInstance()
        if (!session.setCategory(AVAudioSessionCategoryPlayback, mode = AVAudioSessionModeSpokenAudio, options = 0u, error = null)) return
        if (!session.setActive(active = true, error = null)) return

        val decoder = IosVoiceCodec.newDecoder()
        val player = AVAudioPlayerNode()
        val playbackEngine = AVAudioEngine()
        playbackEngine.attachNode(player)
        playbackEngine.connect(player, to = playbackEngine.mainMixerNode, format = decoder.outputFormat)
        if (!playbackEngine.startAndReturnError(null)) return

        frames.forEach { frame ->
            IosVoiceCodec.decode(decoder, frame)?.let { player.scheduleBuffer(it, completionHandler = null) }
        }
        player.play()
        node = player
        engine = playbackEngine
    }
}
