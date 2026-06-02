package com.app.domain.model

/**
 * Conversation identifier — a sum type over the messenger's chat kinds. Each kind carries a
 * different payload, hence a sealed type (a value class could not express it). Serves as the
 * key for the message timeline and the [Conversation] aggregate.
 */
sealed interface ConversationId {

    /** Shared public mesh chat. */
    data object PublicMesh : ConversationId

    /** Classic channel `#name`. */
    data class Channel(val tag: String) : ConversationId

    /** Private dialog with a specific identity. */
    data class Private(val peer: PeerId) : ConversationId

    /** Geo-chat (Nostr over geohash). */
    data class Geohash(val channel: GeohashChannel) : ConversationId
}
