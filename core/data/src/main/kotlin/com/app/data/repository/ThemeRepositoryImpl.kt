package com.app.data.repository

import com.app.common.settings.SettingsStore
import com.app.domain.model.ThemeMode
import com.app.domain.repository.ThemeRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Theme preference over the shared [SettingsStore] — durable across the Strangler-Fig transition
 * (it does not depend on the legacy `:app` ThemePreferenceManager). Uses the same key and stored
 * value strings as that manager so an existing choice carries over while both still exist.
 */
@SingleIn(AppScope::class)
@Inject
internal class ThemeRepositoryImpl(
    private val settings: SettingsStore,
) : ThemeRepository {

    override fun observeTheme(): Flow<ThemeMode> =
        settings.getStringFlow(KEY_THEME, SYSTEM).map(::parse)

    override suspend fun setTheme(mode: ThemeMode) {
        settings.putString(KEY_THEME, mode.toStored())
    }

    private fun parse(stored: String): ThemeMode = when (stored) {
        LIGHT -> ThemeMode.LIGHT
        DARK -> ThemeMode.DARK
        else -> ThemeMode.SYSTEM
    }

    private fun ThemeMode.toStored(): String = when (this) {
        ThemeMode.SYSTEM -> SYSTEM
        ThemeMode.LIGHT -> LIGHT
        ThemeMode.DARK -> DARK
    }

    private companion object {
        const val KEY_THEME = "theme_preference"
        // Legacy ThemePreference enum names (kept for interop with the still-present app manager).
        const val SYSTEM = "System"
        const val LIGHT = "Light"
        const val DARK = "Dark"
    }
}
