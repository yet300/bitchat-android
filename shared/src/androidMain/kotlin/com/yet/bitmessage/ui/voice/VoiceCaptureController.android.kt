package com.yet.bitmessage.ui.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.app.common.utils.Log
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/**
 * Captures mic PCM (16 kHz mono) and encodes it to raw AAC-LC frames with MediaCodec. The fixed
 * format matches the wire codec [com.app.transport.model.VoiceBurstPacket.Codec.AAC_LC_16K_MONO], so
 * a receiver can decode with a synthesized codec config (no per-stream CSD travels on the wire).
 */
@Composable
actual fun rememberVoiceCaptureController(
    onRequestPermission: suspend () -> Boolean,
): VoiceCaptureController {
    val scope = rememberCoroutineScope()
    val requestPermission = rememberUpdatedState(onRequestPermission)
    val impl = remember { AndroidVoiceCaptureController() }

    return remember(impl, scope) {
        object : VoiceCaptureController {
            override val isRecording: State<Boolean> = impl.isRecording
            override fun start() {
                scope.launch { if (requestPermission.value()) impl.startInternal() }
            }
            override fun stop(): VoiceCapture? = impl.stopInternal()
        }
    }
}

private class AndroidVoiceCaptureController {

    private val _isRecording = mutableStateOf(false)
    val isRecording: State<Boolean> = _isRecording

    @Volatile private var running = false
    private var worker: Thread? = null
    private val frames = mutableListOf<ByteArray>()
    private var startedAt = 0L

    fun startInternal() {
        if (running) return
        running = true
        _isRecording.value = true
        startedAt = System.currentTimeMillis()
        frames.clear()
        worker = thread(name = "voice-capture") { captureLoop() }
    }

    fun stopInternal(): VoiceCapture? {
        if (!running) return null
        running = false
        _isRecording.value = false
        worker?.join(1_000)
        worker = null
        val captured = frames.toList()
        return if (captured.isEmpty()) null else VoiceCapture(captured, (System.currentTimeMillis() - startedAt).toInt())
    }

    private fun captureLoop() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            @Suppress("MissingPermission")
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, PCM_CHUNK),
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord init failed: ${e.message}")
            return
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(encoderFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        try {
            encoder.start()
            record.startRecording()
            val pcm = ByteArray(PCM_CHUNK)
            val info = MediaCodec.BufferInfo()
            while (running) {
                val read = record.read(pcm, 0, pcm.size)
                if (read > 0) feedEncoder(encoder, pcm, read)
                drainEncoder(encoder, info)
            }
            signalEndOfStream(encoder)
            drainEncoder(encoder, info)
        } catch (e: Exception) {
            Log.e(TAG, "Capture loop failed: ${e.message}")
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
    }

    private fun feedEncoder(encoder: MediaCodec, pcm: ByteArray, length: Int) {
        val index = encoder.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return
        val buffer = encoder.getInputBuffer(index) ?: return
        buffer.clear()
        buffer.put(pcm, 0, length)
        encoder.queueInputBuffer(index, 0, length, System.nanoTime() / 1000, 0)
    }

    private fun signalEndOfStream(encoder: MediaCodec) {
        val index = encoder.dequeueInputBuffer(TIMEOUT_US)
        if (index >= 0) encoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    private fun drainEncoder(encoder: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
            if (index < 0) break
            val output = encoder.getOutputBuffer(index)
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (output != null && info.size > 0 && !isConfig) {
                val frame = ByteArray(info.size)
                output.position(info.offset)
                output.get(frame, 0, info.size)
                if (frame.size <= UShort.MAX_VALUE.toInt()) frames += frame
            }
            encoder.releaseOutputBuffer(index, false)
        }
    }

    private fun encoderFormat(): MediaFormat =
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_CHUNK)
        }

    private companion object {
        const val TAG = "VoiceCapture"
        const val SAMPLE_RATE = 16_000
        const val BIT_RATE = 24_000
        const val PCM_CHUNK = 4096
        const val TIMEOUT_US = 10_000L
    }
}
