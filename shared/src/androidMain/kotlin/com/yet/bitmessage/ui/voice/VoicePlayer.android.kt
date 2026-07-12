package com.yet.bitmessage.ui.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.app.common.utils.Log
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Decodes raw AAC-LC (16 kHz mono) frames with MediaCodec and plays them through an AudioTrack. No
 * codec config travels on the wire, so the decoder is primed with the constant AAC-LC 16 kHz mono
 * CSD (`0x14 0x08`) — the counterpart to [rememberVoiceCaptureController]'s fixed encoder format.
 */
@Composable
actual fun rememberVoicePlayer(): VoicePlayer = remember { AndroidVoicePlayer() }

private class AndroidVoicePlayer : VoicePlayer {

    override fun play(frames: List<ByteArray>) {
        if (frames.isEmpty()) return
        thread(name = "voice-play") { runCatching { decodeAndPlay(frames) }.onFailure { Log.e(TAG, "playback failed: ${it.message}") } }
    }

    private fun decodeAndPlay(frames: List<ByteArray>) {
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(decoderFormat(), null, null, 0)
            start()
        }
        val track = buildTrack().apply { play() }
        val info = MediaCodec.BufferInfo()

        try {
            frames.forEachIndexed { index, frame ->
                queueFrame(decoder, frame, endOfStream = index == frames.lastIndex)
                drainToTrack(decoder, track, info)
            }
            drainToTrack(decoder, track, info)
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }
    }

    private fun queueFrame(decoder: MediaCodec, frame: ByteArray, endOfStream: Boolean) {
        val index = decoder.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return
        val buffer = decoder.getInputBuffer(index) ?: return
        buffer.clear()
        buffer.put(frame)
        val flags = if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
        decoder.queueInputBuffer(index, 0, frame.size, 0, flags)
    }

    private fun drainToTrack(decoder: MediaCodec, track: AudioTrack, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
            if (index < 0) break
            val output = decoder.getOutputBuffer(index)
            if (output != null && info.size > 0) {
                val pcm = ByteArray(info.size)
                output.position(info.offset)
                output.get(pcm, 0, info.size)
                track.write(pcm, 0, pcm.size)
            }
            decoder.releaseOutputBuffer(index, false)
        }
    }

    private fun decoderFormat(): MediaFormat =
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x14, 0x08)))
        }

    private fun buildTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private companion object {
        const val TAG = "VoicePlayer"
        const val SAMPLE_RATE = 16_000
        const val TIMEOUT_US = 10_000L
    }
}
