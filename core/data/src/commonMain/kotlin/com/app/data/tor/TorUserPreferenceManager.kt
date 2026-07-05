package com.app.data.tor

import com.app.common.settings.SettingsStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's Tor *preference* (the settings/connectivity toggle), kept separate from the effective
 * Tor mode that actually drives the Arti engine.
 *
 * Mirrors the reference iOS `NetworkActivationService.userTorEnabled` (default ON): the user asks for
 * Tor, but [com.app.data.tor.TorActivationController] only turns the engine on when the activation
 * policy also allows it (location authorized or a mutual favorite). Persisted through the domain
 * [SettingsStore] port; exposed reactively for the activation controller and the settings UI.
 */
@SingleIn(AppScope::class)
@Inject
class TorUserPreferenceManager(
    private val settings: SettingsStore,
) {
    private val _enabled = MutableStateFlow(settings.getBoolean(KEY, DEFAULT))
    val enabledFlow: StateFlow<Boolean> = _enabled.asStateFlow()

    fun get(): Boolean = _enabled.value

    fun set(enabled: Boolean) {
        settings.putBoolean(KEY, enabled)
        _enabled.value = enabled
    }

    private companion object {
        const val KEY = "tor_user_enabled"
        // Default ON, matching the reference iOS default; the policy gate keeps it from actually
        // routing over a not-yet-useful Tor circuit at first launch.
        const val DEFAULT = true
    }
}
