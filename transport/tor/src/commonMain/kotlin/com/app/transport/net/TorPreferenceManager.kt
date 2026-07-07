package com.app.transport.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds the user's Tor [TorMode] preference, persisted through the [TorConfigStore] port.
 *
 * App-scoped singleton: the current value is loaded once on construction and exposed reactively
 * via [modeFlow] for the Tor manager / settings UI.
 */
class TorPreferenceManager(
    private val store: TorConfigStore,
) {

    private val _modeFlow = MutableStateFlow(read())
    val modeFlow: StateFlow<TorMode> = _modeFlow

    fun set(mode: TorMode) {
        store.setTorMode(mode)
        _modeFlow.value = mode
    }

    fun get(): TorMode = read()

    // This is the EFFECTIVE mode that drives the Arti engine, written by
    // TorActivationController (user preference AND activation policy). The user-facing toggle
    // lives in TorUserPreferenceManager. Default OFF so nothing routes over a not-yet-useful
    // Tor circuit before the controller evaluates the policy at launch.
    private fun read(): TorMode = store.getTorMode(TorMode.OFF)
}
