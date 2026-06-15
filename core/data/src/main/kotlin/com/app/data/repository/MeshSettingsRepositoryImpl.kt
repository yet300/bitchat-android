package com.app.data.repository

import com.app.common.settings.SettingsStore
import com.app.domain.repository.MeshSettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow

/**
 * Mesh background preferences over the shared [SettingsStore] — durable across the Strangler-Fig
 * transition (no dependency on the legacy `:app` MeshServicePreferences). Uses the same keys and
 * `true` defaults the foreground service / boot receiver read, so both agree.
 */
@SingleIn(AppScope::class)
@Inject
internal class MeshSettingsRepositoryImpl(
    private val settings: SettingsStore,
) : MeshSettingsRepository {

    override fun observeAutoStart(): Flow<Boolean> = settings.getBooleanFlow(KEY_AUTO_START, defaultValue = true)

    override fun observeBackgroundEnabled(): Flow<Boolean> = settings.getBooleanFlow(KEY_BACKGROUND, defaultValue = true)

    override suspend fun setAutoStart(enabled: Boolean) = settings.putBoolean(KEY_AUTO_START, enabled)

    override suspend fun setBackgroundEnabled(enabled: Boolean) = settings.putBoolean(KEY_BACKGROUND, enabled)

    private companion object {
        const val KEY_AUTO_START = "auto_start_on_boot"
        const val KEY_BACKGROUND = "background_enabled"
    }
}
