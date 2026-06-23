package com.app.data.repository

import com.app.database.dao.SecureSettingDao
import com.app.domain.repository.SettingsRepository
import com.app.common.settings.SettingsStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Ordinary settings. The nickname is a pseudonym but treated as sensitive metadata, so it lives in the
 * encrypted DB ([SecureSettingDao]); the non-threat location toggle stays in plaintext prefs.
 */
@SingleIn(AppScope::class)
@Inject
internal class SettingsRepositoryImpl(
    private val secureSettings: SecureSettingDao,
    private val settings: SettingsStore,
) : SettingsRepository {

    override fun observeNickname(): Flow<String> =
        secureSettings.observe(KEY_NICKNAME).map { it ?: "" }

    override suspend fun setNickname(value: String) {
        secureSettings.put(KEY_NICKNAME, value)
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
