package com.bitchat.android.core.domain.model

/**
 * Идентификатор диалога — сумма видов чата мессенджера. Каждый вид несёт свою нагрузку,
 * поэтому это sealed-тип (value-класс это не выразил бы). Служит ключом ленты сообщений
 * и агрегата [Conversation].
 */
sealed interface ConversationId {

    /** Общий публичный mesh-чат. */
    data object PublicMesh : ConversationId

    /** Классический канал `#name`. */
    data class Channel(val tag: String) : ConversationId

    /** Личный диалог с конкретной личностью. */
    data class Private(val peer: PeerId) : ConversationId

    /** Гео-чат (Nostr поверх geohash). */
    data class Geohash(val channel: GeohashChannel) : ConversationId
}
