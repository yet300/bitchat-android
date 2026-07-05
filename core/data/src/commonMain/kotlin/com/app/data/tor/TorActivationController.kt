package com.app.data.tor

import com.app.common.permission.AppPermission
import com.app.common.permission.PermissionController
import com.app.common.utils.Log
import com.app.data.favorites.FavoritesChangeListener
import com.app.data.favorites.FavoritesPersistenceService
import com.app.transport.net.TorMode
import com.app.transport.net.TorPreferenceManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Dynamic Tor activation, mirroring the reference iOS `NetworkActivationService`.
 *
 * The user's preference ([TorUserPreferenceManager], default ON) is only *effective* when the
 * activation policy also permits it: location is authorized OR there is at least one mutual favorite.
 * The resulting effective mode is written to [TorPreferenceManager], which already drives the Arti
 * engine (start/stop) and resets the HTTP/relay connections on every change — so flipping between
 * Tor and direct transparently reconnects the Nostr relays.
 *
 * Effect: at first launch (no location grant, no favorites) Tor stays off and relays connect
 * directly (fast); Tor engages once the user opens a geohash channel (grants location) or gains a
 * mutual favorite. The location signal comes from the shared Grant-backed [PermissionController]
 * (`observeGranted` polls the live grant state), so no bespoke platform permission code is needed.
 */
@SingleIn(AppScope::class)
@Inject
class TorActivationController(
    private val userPreference: TorUserPreferenceManager,
    private val effectiveMode: TorPreferenceManager,
    private val favorites: FavoritesPersistenceService,
    private val permissions: PermissionController,
    private val scope: CoroutineScope,
) {
    private companion object {
        private const val TAG = "TorActivationController"
    }

    private var started = false

    /** Idempotent. Starts observing preference/policy inputs and driving the effective Tor mode. */
    fun start() {
        if (started) return
        started = true

        // Favorites exposes a listener API rather than a flow; bridge it to a tick the combine re-reads.
        val favoritesTick = MutableStateFlow(0L)
        favorites.addListener(object : FavoritesChangeListener {
            override fun onFavoriteChanged(noiseKeyHex: String) { favoritesTick.value++ }
            override fun onAllCleared() { favoritesTick.value++ }
        })

        scope.launch {
            combine(
                userPreference.enabledFlow,
                permissions.observeGranted(AppPermission.Location),
                favoritesTick,
            ) { userEnabled, locationGranted, _ ->
                userEnabled && (locationGranted || favorites.getMutualFavorites().isNotEmpty())
            }
                .distinctUntilChanged()
                .collect { desired ->
                    Log.i(TAG, "Tor activation policy -> ${if (desired) "ON (route via Tor)" else "OFF (direct)"}")
                    effectiveMode.set(if (desired) TorMode.ON else TorMode.OFF)
                }
        }
    }
}
