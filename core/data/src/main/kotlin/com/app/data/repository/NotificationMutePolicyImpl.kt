package com.app.data.repository

import com.app.common.settings.SettingsStore
import com.app.domain.model.ConversationId
import com.app.domain.model.PeerId
import com.app.domain.repository.NotificationMutePolicy
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Reads the same persisted mute state the UI writes ([MutePrefsKeys]) — synchronously, on demand —
 * so the notifier and the conversation-list mute can never drift.
 */
@SingleIn(AppScope::class)
@Inject
internal class NotificationMutePolicyImpl(
    private val settings: SettingsStore,
) : NotificationMutePolicy {

    override fun isAllMuted(): Boolean = settings.getBoolean(MutePrefsKeys.GLOBAL_MUTE, false)

    override fun isPrivateMuted(peerId: String): Boolean =
        ConversationId.Private(PeerId(peerId)) in mutedSet()

    override fun isGeohashMuted(geohash: String): Boolean =
        mutedSet().any { it is ConversationId.Geohash && it.channel.geohash == geohash }

    private fun mutedSet(): Set<ConversationId> =
        MutePrefsKeys.decodeSet(settings.getString(MutePrefsKeys.MUTED, ""))
}
