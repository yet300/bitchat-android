@file:OptIn(ExperimentalTime::class)

package com.bitchat.android.core.domain.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Message content type. */
enum class MessageType { TEXT, AUDIO, IMAGE, FILE }

/**
 * Lightweight reference to the sender — so the UI doesn't hit repositories for a name on every
 * render. [peerId] == null for system messages.
 */
data class SenderRef(
    val peerId: PeerId?,
    val displayName: String,
) {
    companion object {
        const val SYSTEM_NAME = "system"
        val SYSTEM = SenderRef(peerId = null, displayName = SYSTEM_NAME)
    }
}

/**
 * The messenger's message domain entity (named bitMessage). Pure: it does NOT contain binary
 * packing (the wire codec `toBinaryPayload` stays in a data mapper to preserve byte compatibility).
 */
data class BitMessage(
    val id: String,
    val conversationId: ConversationId,
    val sender: SenderRef,
    val content: String,
    val timestamp: Instant,
    val type: MessageType = MessageType.TEXT,
    val isMine: Boolean = false,
    val isRelay: Boolean = false,
    val mentions: List<String> = emptyList(),
    val deliveryStatus: DeliveryStatus? = null,
    val attachment: Attachment? = null,
    val powDifficulty: Int? = null,
) {
    val isSystem: Boolean get() = sender.peerId == null
}
