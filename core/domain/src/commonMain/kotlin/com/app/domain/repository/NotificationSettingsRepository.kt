package com.app.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Persisted notification preferences. Global mute suppresses all in-app notifications
 * regardless of per-conversation mute flags.
 */
interface NotificationSettingsRepository {

    fun observeGlobalMuteEnabled(): Flow<Boolean>

    suspend fun setGlobalMuteEnabled(enabled: Boolean)
}
