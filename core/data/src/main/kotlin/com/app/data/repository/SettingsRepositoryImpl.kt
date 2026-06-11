package com.app.data.repository

import com.app.domain.repository.SettingsRepository
import com.app.common.settings.SettingsStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow

/**
 * Ordinary (non-secret) settings over the shared [SettingsStore]. Keys match the legacy
 * `DataManager` so both read the same store during the Strangler-Fig transition.
 */
@SingleIn(AppScope::class)
@Inject
internal class SettingsRepositoryImpl(
    private val settings: SettingsStore,
) : SettingsRepository {

    override fun observeNickname(): Flow<String> =
        settings.getStringFlow(KEY_NICKNAME, "")

    override suspend fun setNickname(value: String) {
        settings.putString(KEY_NICKNAME, value)
    }

    override var locationServicesEnabled: Boolean
        get() = settings.getBoolean(KEY_LOCATION_ENABLED, true)
        set(value) {
            settings.putBoolean(KEY_LOCATION_ENABLED, value)
        }

    private companion object {
        const val KEY_NICKNAME = "nickname"
        const val KEY_LOCATION_ENABLED = "location_services_enabled"
    }
}
