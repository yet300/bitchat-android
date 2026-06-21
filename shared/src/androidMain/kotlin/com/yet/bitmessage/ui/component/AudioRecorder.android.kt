package com.yet.bitmessage.ui.component

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.app.common.utils.Log
import java.io.File

@Composable
actual fun rememberAudioRecorderController(): AudioRecorderController {
    val context = LocalContext.current
    val impl = remember { AndroidAudioRecorderController(context.applicationContext) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) impl.startInternal()
    }

    DisposableEffect(impl) { onDispose { impl.release() } }

    return remember(impl, permLauncher) {
        object : AudioRecorderController {
            override val isRecording: State<Boolean> = impl.isRecording

            override fun start() {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    impl.startInternal()
                } else {
                    permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }

            override fun stop(): String? = impl.stopInternal()
        }
    }
}

private class AndroidAudioRecorderController(private val context: Context) {

    private val _isRecording = mutableStateOf(false)
    val isRecording: State<Boolean> = _isRecording

    private var recorder: MediaRecorder? = null
    private var outFile: File? = null

    fun startInternal() {
        if (_isRecording.value) return
        try {
            val dir = File(context.filesDir, "voicenotes/outgoing").apply { mkdirs() }
            val name = "voice_${System.currentTimeMillis()}.m4a"
            val file = File(dir, name)
            @Suppress("DEPRECATION")
            val rec = MediaRecorder()
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(1)
            rec.setAudioSamplingRate(16_000)
            rec.setAudioEncodingBitRate(20_000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            outFile = file
            _isRecording.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}")
        }
    }

    fun stopInternal(): String? {
        if (!_isRecording.value) return null
        val path = outFile?.absolutePath
        try {
            recorder?.apply {
                try { stop() } catch (_: Exception) {}
                try { reset() } catch (_: Exception) {}
                try { release() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        recorder = null
        outFile = null
        _isRecording.value = false
        return path?.takeIf { File(it).exists() && File(it).length() > 0 }
    }

    fun release() {
        stopInternal()
    }

    private companion object {
        const val TAG = "AudioRecorder"
    }
}
