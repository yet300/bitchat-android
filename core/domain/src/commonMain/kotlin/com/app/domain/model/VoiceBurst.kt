package com.app.domain.model

/**
 * A fully reassembled inbound voice burst: the ordered AAC-LC frames of one utterance from [peerId].
 * Binary by nature (audio), which is why the voice seam is the one place raw bytes cross the domain.
 * Live-only — never persisted or compared, so no structural equals is provided.
 */
class VoiceBurst(
    val peerId: String,
    val frames: List<ByteArray>,
    val durationMs: Int,
)
