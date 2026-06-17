package com.app.domain.repository

/**
 * Synchronous gate the (no-UI) notification emit path consults before raising a notification.
 * Combines the global mute flag ([NotificationSettingsRepository]) with the per-conversation mute
 * set ([ConversationPrefsRepository]) — both persisted, read on demand.
 *
 * Primitive call-site shapes (peerId / geohash strings) rather than ConversationId, because the
 * notifier works in those terms; geohash matching ignores the precision level.
 */
interface NotificationMutePolicy {

    /** All notifications globally muted. */
    fun isAllMuted(): Boolean

    /** A private chat with [peerId] is muted. */
    fun isPrivateMuted(peerId: String): Boolean

    /** A geohash chat for [geohash] is muted (any precision level). */
    fun isGeohashMuted(geohash: String): Boolean
}
