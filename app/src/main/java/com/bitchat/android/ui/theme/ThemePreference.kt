package com.bitchat.android.ui.theme

import com.russhwolf.settings.ObservableSettings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * App theme preference: System default, Light, or Dark.
 */
enum class ThemePreference {
    System,
    Light,
    Dark;

    val isSystem : Boolean get() = this == System
    val isLight : Boolean get() = this == Light
    val isDark : Boolean get() = this == Dark
}

/**
 * Graph-owned theme preference with a StateFlow; settings are injected
 * (formerly an `object` calling the global appSettings()).
 */
@SingleIn(AppScope::class)
@Inject
class ThemePreferenceManager(private val settings: ObservableSettings) {

    private companion object {
        const val KEY_THEME = "theme_preference"
    }

    private val _themeFlow = MutableStateFlow(load())
    val themeFlow: StateFlow<ThemePreference> = _themeFlow

    private fun load(): ThemePreference {
        val saved = settings.getString(KEY_THEME, ThemePreference.System.name)
        return runCatching { ThemePreference.valueOf(saved) }.getOrDefault(ThemePreference.System)
    }

    fun set(preference: ThemePreference) {
        settings.putString(KEY_THEME, preference.name)
        _themeFlow.value = preference
    }
}
