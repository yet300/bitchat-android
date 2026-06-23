package com.app.data.repository

import com.app.database.dao.ConversationPrefDao
import com.app.domain.model.ConversationId
import com.app.domain.repository.ConversationPrefsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Pin/mute flags in the encrypted DB ([ConversationPrefDao]) — these reveal the contact graph, so they
 * are sensitive metadata and no longer live in plaintext prefs. Keys use the stable [MutePrefsKeys]
 * encoding, so the mute set written here is the mute set [NotificationMutePolicyImpl] honors.
 */
@SingleIn(AppScope::class)
@Inject
internal class ConversationPrefsRepositoryImpl(
    private val prefs: ConversationPrefDao,
) : ConversationPrefsRepository {

    override fun observePinned(): Flow<Set<ConversationId>> = observeSet(pinned = true)

    override fun observeMuted(): Flow<Set<ConversationId>> = observeSet(pinned = false)

    override suspend fun setPinned(id: ConversationId, pinned: Boolean) {
        prefs.setPinned(MutePrefsKeys.encode(id), pinned)
    }

    override suspend fun setMuted(id: ConversationId, muted: Boolean) {
        prefs.setMuted(MutePrefsKeys.encode(id), muted)
    }

    private fun observeSet(pinned: Boolean): Flow<Set<ConversationId>> {
        val flow = if (pinned) prefs.observePinned() else prefs.observeMuted()
        return flow.map { tokens -> tokens.mapNotNull(MutePrefsKeys::decode).toSet() }
    }
}
