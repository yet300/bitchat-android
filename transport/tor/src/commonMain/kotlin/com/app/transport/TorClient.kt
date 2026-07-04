package com.app.transport

import com.app.common.AppDispatchers
import com.app.common.settings.SettingsStore
import com.app.transport.TorClient.Companion.create
import com.app.transport.net.ArtiTorManager
import com.app.transport.net.HttpClientProvider
import com.app.transport.net.TorDataDirProvider
import com.app.transport.net.TorPreferenceManager
import com.app.transport.net.WebSocketClientProvider
import io.ktor.client.engine.HttpClientEngineFactory

/**
 * Configuration for [TorClient.create]. Group the platform seams the Tor stack needs; everything
 * except [dispatchers] is required.
 *
 * @property settings persistent key-value store the [TorPreferenceManager] reads/writes the saved
 *   [com.app.transport.net.TorMode] from. Supply your app's [SettingsStore].
 * @property dataDir absolute path of the Arti data directory (created on demand). On Android use
 *   `File(context.filesDir, "arti").absolutePath`; on Apple use `nativeArtiDataDir()`.
 * @property engineFactory the ktor HTTP engine. Pass `androidHttpClientEngineFactory()` on Android
 *   or `darwinHttpClientEngineFactory()` on Apple — both ship in this module's platform source sets.
 * @property dispatchers coroutine dispatchers; defaults to a fresh [AppDispatchers].
 *
 * Example:
 * ```
 * val tor = TorClient.create(
 *     TorConfig(
 *         settings = appSettings,
 *         dataDir = { File(context.filesDir, "arti").apply { mkdirs() }.absolutePath },
 *         engineFactory = androidHttpClientEngineFactory(),
 *     ),
 * )
 * tor.torManager.init()
 * val httpClient = tor.httpClientProvider // HTTP over Tor once a mode is applied
 * ```
 */
class TorConfig(
    val settings: SettingsStore,
    val dataDir: TorDataDirProvider,
    val engineFactory: HttpClientEngineFactory<*>,
    val dispatchers: AppDispatchers = AppDispatchers(),
)

/**
 * DI-agnostic entry point for the Tor/HTTP stack. [create] wires the manager and the HTTP client
 * provider — including the mutual dependency between them (the manager resets the HTTP client's
 * cached clients on circuit changes, while the HTTP client reads the manager's live SOCKS address
 * per connection) — so consumers never assemble the seam by hand.
 *
 * The advanced pieces are exposed as properties for callers that only need part of the stack.
 */
class TorClient private constructor(
    val torManager: ArtiTorManager,
    val httpClientProvider: HttpClientProvider,
    val torPreferenceManager: TorPreferenceManager,
) {
    /** WebSocket client seam (Nostr relays), backed by the same Tor-aware HTTP provider. */
    val webSocketClientProvider: WebSocketClientProvider get() = httpClientProvider

    companion object {
        fun create(config: TorConfig): TorClient {
            val preferences = TorPreferenceManager(config.settings)
            // Late binding reproduces the graph's Lazy cycle break: the SOCKS address is read
            // per connection (by which time the manager exists), and the reset callback only
            // fires on circuit changes (after both objects are constructed).
            lateinit var manager: ArtiTorManager
            val http = HttpClientProvider(
                socksAddressSource = { manager.currentSocksAddress() },
                engineFactory = config.engineFactory,
            )
            manager = ArtiTorManager(
                dataDirProvider = config.dataDir,
                torPreferenceManager = preferences,
                httpReset = { http.reset() },
                dispatchers = config.dispatchers,
            )
            return TorClient(manager, http, preferences)
        }
    }
}
