package com.app.data.repository

import com.app.common.settings.SettingsStore
import com.app.domain.repository.NotificationSettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow

@SingleIn(AppScope::class)
@Inject
internal class NotificationSettingsRepositoryImpl(
    private val settings: SettingsStore,
) : NotificationSettingsRepository {

    override fun observeGlobalMuteEnabled(): Flow<Boolean> =
        settings.getBooleanFlow(MutePrefsKeys.GLOBAL_MUTE, defaultValue = false)

    override suspend fun setGlobalMuteEnabled(enabled: Boolean) =
        settings.putBoolean(MutePrefsKeys.GLOBAL_MUTE, enabled)
}
