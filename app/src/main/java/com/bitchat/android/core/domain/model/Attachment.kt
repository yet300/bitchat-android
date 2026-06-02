package com.bitchat.android.core.domain.model

/** Вид вложения. */
enum class AttachmentKind { AUDIO, IMAGE, FILE }

/** Прогресс передачи вложения (BLE/Nostr). */
sealed interface TransferState {
    data object Idle : TransferState
    data class InProgress(val sent: Long, val total: Long) : TransferState
    data object Done : TransferState
    data class Failed(val reason: String) : TransferState
    data object Cancelled : TransferState
}

/**
 * Вложение сообщения. В domain храним ссылку (локальный путь/идентификатор), а не байты —
 * работа с файлами/потоками остаётся инфраструктурой.
 */
data class Attachment(
    val kind: AttachmentKind,
    val ref: String,
    val mime: String? = null,
    val sizeBytes: Long? = null,
    val transfer: TransferState = TransferState.Idle,
)
