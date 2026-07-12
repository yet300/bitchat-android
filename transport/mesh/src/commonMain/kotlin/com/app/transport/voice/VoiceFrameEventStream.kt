package com.app.transport.voice

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A live public voice packet accepted by the mesh; it is deliberately not persisted. */
data class PublicVoiceFrame(
    val peerId: String,
    val payload: ByteArray,
    val timestampMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as PublicVoiceFrame

        if (timestampMs != other.timestampMs) return false
        if (peerId != other.peerId) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = timestampMs.hashCode()
        result = 31 * result + peerId.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/** Broadcast-only hand-off from the mesh to a future audio consumer. */
class VoiceFrameEventStream {
    private val _frames = MutableSharedFlow<PublicVoiceFrame>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val frames: SharedFlow<PublicVoiceFrame> = _frames.asSharedFlow()

    fun emit(frame: PublicVoiceFrame) {
        _frames.tryEmit(frame)
    }
}
