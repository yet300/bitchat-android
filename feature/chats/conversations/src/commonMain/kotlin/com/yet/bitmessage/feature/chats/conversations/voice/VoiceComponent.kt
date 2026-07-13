package com.yet.bitmessage.feature.chats.conversations.voice

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import kotlinx.coroutines.flow.Flow

/**
 * Live public voice (0x29): a push-to-talk broadcaster plus an activity log of inbound bursts.
 * Capture/encode and decode/playback are platform concerns owned by the UI ([playback] hands the
 * decoded-ready frames to the composable's player); this component only bridges them to the mesh via
 * [VoiceRepository][com.app.domain.repository.VoiceRepository]. Nothing is persisted.
 */
interface VoiceComponent {

    val model: Value<Model>

    /** Inbound bursts to play, surfaced as one-shot events for the UI's platform player. */
    val playback: Flow<List<ByteArray>>

    /** Hand a captured PTT burst (already-encoded AAC-LC frames) to the mesh. */
    fun onBurstCaptured(frames: List<ByteArray>, durationMs: Int)

    /** Requests the microphone permission; the UI records only once granted. */
    suspend fun requestMicrophonePermission(): Boolean

    fun onCloseClicked()

    data class Model(
        val received: List<ReceivedBurst>,
    )

    data class ReceivedBurst(val peerId: String, val durationMs: Int)

    fun interface Factory {
        fun create(
            componentContext: ComponentContext,
            onClose: () -> Unit,
        ): VoiceComponent
    }
}
