package com.app.transport.nostr

/**
 * Persistence port for the Nostr stack's durable state: PoW preferences, the relay directory's
 * last-update timestamp and the two geohash registry JSON maps. The transport SDK owns no storage:
 * the interface lives here next to its consumers and the app supplies the implementation
 * (bitMessage backs it with the :core:data settings store).
 *
 * The registry values are opaque JSON strings — the registries own the (de)serialization; the
 * store only persists them. All members are synchronous: consumers read once on construction and
 * keep their own in-memory state, so implementations must not block on I/O beyond a local
 * key-value read/write.
 */
interface NostrConfigStore {

    fun getPowEnabled(default: Boolean): Boolean

    fun setPowEnabled(value: Boolean)

    fun getPowDifficulty(default: Int): Int

    fun setPowDifficulty(value: Int)

    fun getRelayLastUpdateMs(default: Long): Long

    fun setRelayLastUpdateMs(value: Long)

    fun getAliasRegistryJson(): String?

    fun setAliasRegistryJson(json: String)

    fun clearAliasRegistryJson()

    fun getConversationRegistryJson(): String?

    fun setConversationRegistryJson(json: String)

    fun clearConversationRegistryJson()
}
