package com.app.transport.routing

/**
 * Transport-layer envelope for all outgoing messages.
 *
 * Domain-agnostic vocabulary (String peerID, not PeerId value-class) — translation
 * between domain types and envelopes is done in the :core:data adapter layer.
 * Introduced as part of §6 Bearer SPI / RouteStrategy refactoring.
 */
sealed class OutgoingEnvelope {
    abstract val peerID: String

    /** Encrypted private message to a single peer. */
    data class Private(
        override val peerID: String,
        val content: String,
        val recipientNickname: String,
        val messageId: String,
    ) : OutgoingEnvelope()

    /** Read receipt acknowledging a previously received message. */
    data class Receipt(
        override val peerID: String,
        val originalMessageId: String,
    ) : OutgoingEnvelope()

    /** Delivery acknowledgement for a specific message. */
    data class Ack(
        override val peerID: String,
        val messageId: String,
    ) : OutgoingEnvelope()

    /** Favorite-status notification (mutual favorites UI/protocol). */
    data class Favorite(
        override val peerID: String,
        val isFavorite: Boolean,
    ) : OutgoingEnvelope()
}
