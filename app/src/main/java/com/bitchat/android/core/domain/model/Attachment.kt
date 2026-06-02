package com.bitchat.android.core.domain.model

/** Attachment kind. */
enum class AttachmentKind { AUDIO, IMAGE, FILE }

/** Attachment transfer progress (BLE/Nostr). */
sealed interface TransferState {
    data object Idle : TransferState
    data class InProgress(val sent: Long, val total: Long) : TransferState
    data object Done : TransferState
    data class Failed(val reason: String) : TransferState
    data object Cancelled : TransferState
}

/**
 * Message attachment. The domain keeps a reference (local path / id), not bytes — file/stream
 * handling stays in infrastructure.
 */
data class Attachment(
    val kind: AttachmentKind,
    val ref: String,
    val mime: String? = null,
    val sizeBytes: Long? = null,
    val transfer: TransferState = TransferState.Idle,
)
