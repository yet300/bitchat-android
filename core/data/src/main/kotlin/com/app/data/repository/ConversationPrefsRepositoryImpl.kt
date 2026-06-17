package com.app.data.repository

import com.app.common.settings.SettingsStore
import com.app.domain.model.ConversationId
import com.app.domain.repository.ConversationPrefsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Pin/mute flags over the shared [SettingsStore]. Each set is a newline-joined list of stable
 * [ConversationId] keys (see [MutePrefsKeys]), read reactively via the store's flow. The same codec
 * is consumed by [NotificationMutePolicyImpl], so mute set here == mute honored by notifications.
 */
@SingleIn(AppScope::class)
@Inject
internal class ConversationPrefsRepositoryImpl(
    private val settings: SettingsStore,
) : ConversationPrefsRepository {

    override fun observePinned(): Flow<Set<ConversationId>> = observeSet(MutePrefsKeys.PINNED)

    override fun observeMuted(): Flow<Set<ConversationId>> = observeSet(MutePrefsKeys.MUTED)

    override suspend fun setPinned(id: ConversationId, pinned: Boolean) = setFlag(MutePrefsKeys.PINNED, id, pinned)

    override suspend fun setMuted(id: ConversationId, muted: Boolean) = setFlag(MutePrefsKeys.MUTED, id, muted)

    private fun observeSet(key: String): Flow<Set<ConversationId>> =
        settings.getStringFlow(key, "").map(MutePrefsKeys::decodeSet)

    private fun setFlag(key: String, id: ConversationId, on: Boolean) {
        val current = MutePrefsKeys.decodeSet(settings.getString(key, "")).toMutableSet()
        if (on) current.add(id) else current.remove(id)
        settings.putString(key, MutePrefsKeys.encodeSet(current))
    }
}
