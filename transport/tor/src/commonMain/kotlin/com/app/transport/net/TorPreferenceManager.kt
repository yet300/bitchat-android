package com.app.transport.net

import com.app.common.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Persists the user's Tor [TorMode] preference through the domain [SettingsStore] port.
 *
 * App-scoped singleton: the current value is loaded once on construction and exposed reactively
 * via [modeFlow] for the Tor manager / settings UI.
 */
class TorPreferenceManager(
    private val settings: SettingsStore,
) {

    private val _modeFlow = MutableStateFlow(read())
    val modeFlow: StateFlow<TorMode> = _modeFlow

    fun set(mode: TorMode) {
        settings.putString(KEY_TOR_MODE, mode.name)
        _modeFlow.value = mode
    }

    fun get(): TorMode = read()

    private fun read(): TorMode {
        // Default OFF: relays/DM connect directly (fast, reliable) out of the box. Routing Nostr
        // over the bundled Arti SOCKS proxy is slow to bootstrap (~30-120s) and drops many circuits
        // ("Protocol error while launching a data stream"), which stalls geohash presence and DMs.
        // Tor stays user-toggleable in settings for those who want it.
        val saved = settings.getString(KEY_TOR_MODE, TorMode.OFF.name)
        return runCatching { TorMode.valueOf(saved) }.getOrDefault(TorMode.OFF)
    }

    private companion object {
        const val KEY_TOR_MODE = "tor_mode"
    }
}
