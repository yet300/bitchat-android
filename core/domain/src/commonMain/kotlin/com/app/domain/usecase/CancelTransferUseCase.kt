package com.app.domain.usecase

import com.app.domain.repository.MessageRepository
import com.app.domain.repository.MessageTransport

/**
 * Cancel an in-flight attachment transfer and, only if the transport actually cancelled it,
 * remove the message from the timeline.
 */
class CancelTransferUseCase(
    private val transport: MessageTransport,
    private val messages: MessageRepository,
) {
    /** Returns true if the transfer was cancelled. */
    suspend operator fun invoke(messageId: String): Boolean {
        val cancelled = transport.cancelTransfer(messageId)
        if (cancelled) messages.remove(messageId)
        return cancelled
    }
}
