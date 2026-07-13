package com.yet.bitmessage.ui.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.launch
import platform.AVFAudio.*
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.*
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberVoiceCaptureController(
    onRequestPermission: suspend () -> Boolean,
): VoiceCaptureController {
    val scope = rememberCoroutineScope()
    val requestPermission = rememberUpdatedState(onRequestPermission)
    val impl = remember { IosVoiceCaptureController() }

    return remember(impl, scope) {
        object : VoiceCaptureController {
            override val isRecording: State<Boolean> = impl.isRecording

            override fun start() {
                scope.launch {
                    if (requestPermission.value()) impl.startInternal()
                }
            }

            override fun stop(): VoiceCapture? = impl.stopInternal()
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosVoiceCaptureController {
    private val recording = mutableStateOf(false)
    val isRecording: State<Boolean> = recording

    private var engine: AVAudioEngine? = null
    private var converter: AVAudioConverter? = null
    private var startedAtMs = 0L
    private val frames = mutableListOf<ByteArray>()
    private val framesLock = NSLock()

    fun startInternal() {
        if (recording.value) return
        val session = AVAudioSession.sharedInstance()
        if (!session.setCategory(
            category = AVAudioSessionCategoryPlayAndRecord,
            mode = AVAudioSessionModeDefault,
            options = 0u,
            error = null,
        )) return
        if (!session.overrideOutputAudioPort(AVAudioSessionPortOverrideSpeaker, error = null)) return
        if (!session.setActive(active = true, withOptions = 0u, error = null)) return

        val captureEngine = AVAudioEngine()
        val input = captureEngine.inputNode
        val inputFormat = input.outputFormatForBus(0u)
        val encoder = IosVoiceCodec.newEncoder(inputFormat) ?: run {
            session.setActive(active = false, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
            return
        }

        framesLock.lock()
        frames.clear()
        framesLock.unlock()
        converter = encoder
        engine = captureEngine
        startedAtMs = NSDate().timeIntervalSince1970.times(1_000).toLong()
        recording.value = true

        input.installTapOnBus(0u, bufferSize = 4096u, format = inputFormat) { buffer, _ ->
            if (recording.value) buffer?.let { pcm ->
                val encoded = IosVoiceCodec.encode(encoder, pcm)
                if (encoded.isNotEmpty()) {
                    framesLock.lock()
                    frames += encoded
                    framesLock.unlock()
                }
            }
        }
        captureEngine.prepare()
        if (!captureEngine.startAndReturnError(null)) {
            input.removeTapOnBus(0u)
            recording.value = false
            converter = null
            engine = null
        }
    }

    fun stopInternal(): VoiceCapture? {
        if (!recording.value) return null
        recording.value = false
        engine?.let {
            it.inputNode.removeTapOnBus(0u)
            it.stop()
        }
        engine = null
        converter = null
        AVAudioSession.sharedInstance().setActive(active = false, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
        framesLock.lock()
        val captured = frames.toList()
        framesLock.unlock()
        return captured.takeIf { it.isNotEmpty() }?.let {
            VoiceCapture(it, (NSDate().timeIntervalSince1970.times(1_000).toLong() - startedAtMs).toInt())
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal object IosVoiceCodec {
    private const val sampleRate = 16_000.0
    private const val channelCount = 1u
    private const val bitRate = 24_000
    private const val samplesPerFrame = 1_024u

    fun newEncoder(inputFormat: AVAudioFormat): AVAudioConverter? =
        AVAudioConverter(fromFormat = inputFormat, toFormat = aacFormat()).also { it.bitRate = bitRate.toLong() }

    fun newDecoder(): AVAudioConverter = AVAudioConverter(fromFormat = aacFormat(), toFormat = pcmFormat())

    fun aacFormat(): AVAudioFormat = memScoped {
        val asbd = alloc<AudioStreamBasicDescription>().apply {
            mSampleRate = sampleRate
            mFormatID = kAudioFormatMPEG4AAC
            mFormatFlags = 0u
            mBytesPerPacket = 0u
            mFramesPerPacket = samplesPerFrame
            mBytesPerFrame = 0u
            mChannelsPerFrame = channelCount
            mBitsPerChannel = 0u
            mReserved = 0u
        }
        AVAudioFormat(asbd.ptr)
    }

    private fun pcmFormat(): AVAudioFormat = AVAudioFormat(standardFormatWithSampleRate = sampleRate, channels = channelCount)

    fun encode(converter: AVAudioConverter, input: AVAudioPCMBuffer): List<ByteArray> {
        val output = AVAudioCompressedBuffer(
            format = converter.outputFormat,
            packetCapacity = 8u,
            maximumPacketSize = maxOf(converter.maximumOutputPacketSize, 1),
        )
        var consumed = false
        val status = converter.convertToBuffer(output, error = null) { _, inputStatus ->
            if (consumed) {
                inputStatus?.pointed?.value = AVAudioConverterInputStatus_NoDataNow
                null
            } else {
                consumed = true
                inputStatus?.pointed?.value = AVAudioConverterInputStatus_HaveData
                input
            }
        }
        if (status != AVAudioConverterOutputStatus_HaveData || output.packetCount == 0u) return emptyList()
        val descriptions = output.packetDescriptions ?: return emptyList()
        val data = output.audioBufferList?.pointed?.mBuffers?.get(0)?.mData?.reinterpret<ByteVar>() ?: return emptyList()
        return List(output.packetCount.toInt()) { index ->
            val description = descriptions[index]
            description.mDataByteSize.toInt().takeIf { it > 0 }?.let { size ->
                ByteArray(size).also { bytes ->
                    bytes.usePinned { pinned ->
                        memcpy(pinned.addressOf(0), data.plus(description.mStartOffset), size.toULong())
                    }
                }
            } ?: ByteArray(0)
        }.filter(ByteArray::isNotEmpty)
    }

    fun decode(converter: AVAudioConverter, frame: ByteArray): AVAudioPCMBuffer? {
        if (frame.isEmpty()) return null
        val input = AVAudioCompressedBuffer(
            format = converter.inputFormat,
            packetCapacity = 1u,
            maximumPacketSize = frame.size.toLong(),
        )
        val data = input.audioBufferList?.pointed?.mBuffers?.get(0)?.mData?.reinterpret<ByteVar>() ?: return null
        frame.usePinned { pinned -> memcpy(data, pinned.addressOf(0), frame.size.toULong()) }
        input.byteLength = frame.size.toUInt()
        input.packetCount = 1u
        input.packetDescriptions?.pointed?.apply {
            mStartOffset = 0
            mVariableFramesInPacket = 0u
            mDataByteSize = frame.size.toUInt()
        } ?: return null

        val output = AVAudioPCMBuffer(pCMFormat = converter.outputFormat, frameCapacity = samplesPerFrame * 2u)
        var consumed = false
        val status = converter.convertToBuffer(output, error = null) { _, inputStatus ->
            if (consumed) {
                inputStatus?.pointed?.value = AVAudioConverterInputStatus_NoDataNow
                null
            } else {
                consumed = true
                inputStatus?.pointed?.value = AVAudioConverterInputStatus_HaveData
                input
            }
        }
        return output.takeIf { status == AVAudioConverterOutputStatus_HaveData && it.frameLength > 0u }
    }
}
