package com.app.domain.repository

import com.app.domain.model.VoiceBurst
import kotlinx.coroutines.flow.Flow

/**
 * Live public voice (0x29) — the thin business seam over the mesh voice transport. It deals only in
 * already-encoded audio frames: packaging outbound frames into wire bursts (Start → Frames → End)
 * and reassembling inbound bursts. The audio codec (PCM ⇄ AAC-LC) and capture/playback are platform
 * concerns owned by the UI layer, not here. Nothing is persisted — voice is ephemeral.
 */
interface VoiceRepository {

    /** Completed inbound bursts, reassembled from 0x29 frames (our own echoes are excluded upstream). */
    val incomingBursts: Flow<VoiceBurst>

    /**
     * Broadcasts one live burst: a Start marker (announcing the codec), the [frames] chunked to the
     * wire limits, then an End marker carrying [durationMs]. A fresh burst id groups them on receive.
     */
    suspend fun broadcast(frames: List<ByteArray>, durationMs: Int)
}
