package com.app.data.repository

import com.app.common.settings.SettingsStore
import com.app.domain.repository.OnboardingRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow

/**
 * Onboarding completion flag over the shared [SettingsStore] — durable across the Strangler-Fig
 * transition (no dependency on the legacy `:app` onboarding preferences). Defaults to `false` so a
 * fresh install starts in the onboarding flow.
 */
@SingleIn(AppScope::class)
@Inject
internal class OnboardingRepositoryImpl(
    private val settings: SettingsStore,
) : OnboardingRepository {

    override fun isCompleted(): Boolean = settings.getBoolean(KEY_COMPLETED, defaultValue = false)

    override fun observeCompleted(): Flow<Boolean> = settings.getBooleanFlow(KEY_COMPLETED, defaultValue = false)

    override suspend fun setCompleted() = settings.putBoolean(KEY_COMPLETED, true)

    private companion object {
        const val KEY_COMPLETED = "onboarding_completed"
    }
}
