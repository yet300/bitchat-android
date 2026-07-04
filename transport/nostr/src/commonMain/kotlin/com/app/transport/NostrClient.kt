package com.app.transport

import com.app.common.AppDispatchers
import com.app.common.settings.SettingsStore
import com.app.crypto.identity.SecureIdentityStateManager
import com.app.transport.net.HttpClientProvider
import com.app.transport.nostr.GeohashAliasRegistry
import com.app.transport.nostr.GeohashConversationRegistry
import com.app.transport.nostr.LocationNotesManager
import com.app.transport.nostr.NostrEventDeduplicator
import com.app.transport.nostr.NostrIdentityBridge
import com.app.transport.nostr.NostrRelayManager
import com.app.transport.nostr.NostrSubscriptionManager
import com.app.transport.nostr.PoWPreferenceManager
import com.app.transport.nostr.RelayDirectory
import com.app.transport.nostr.RelayDirectoryStorage
import kotlinx.coroutines.CoroutineScope

/**
 * Configuration for [NostrClient.create]. Groups the app seams the Nostr stack needs; everything
 * except [dispatchers] and [eventCacheCapacity] is required.
 *
 * @property settings persistent key-value store used by the relay directory, geohash registries and
 *   PoW preferences.
 * @property http the Tor-aware HTTP client provider — pass `TorClient.httpClientProvider`. It backs
 *   both the relay directory (HTTP) and the relay manager (WebSocket).
 * @property relayStorage platform relay-CSV storage: `AndroidRelayDirectoryStorage(context)` on
 *   Android, `NativeRelayDirectoryStorage()` on Apple.
 * @property stateManager the crypto identity store, used to derive the current Nostr identity.
 * @property scope long-lived coroutine scope the subscription manager runs on (e.g. an app scope).
 * @property dispatchers coroutine dispatchers; defaults to a fresh [AppDispatchers].
 * @property eventCacheCapacity dedup ring size for incoming relay events; defaults to the stack's own.
 *
 * Example:
 * ```
 * val nostr = NostrClient.create(
 *     NostrConfig(
 *         settings = appSettings,
 *         http = tor.httpClientProvider,
 *         relayStorage = AndroidRelayDirectoryStorage(context),
 *         stateManager = secureIdentityStateManager,
 *         scope = appScope,
 *     ),
 * )
 * ```
 */
class NostrConfig(
    val settings: SettingsStore,
    val http: HttpClientProvider,
    val relayStorage: RelayDirectoryStorage,
    val stateManager: SecureIdentityStateManager,
    val scope: CoroutineScope,
    val dispatchers: AppDispatchers = AppDispatchers(),
    val eventCacheCapacity: Int? = null,
)

/**
 * DI-agnostic entry point for the Nostr stack. [create] wires the relay manager, relay directory,
 * subscription manager, geohash registries, PoW preferences, location notes and the identity bridge
 * from the seams in [NostrConfig]. Each is exposed as a property for advanced callers.
 */
class NostrClient private constructor(
    val relayManager: NostrRelayManager,
    val relayDirectory: RelayDirectory,
    val subscriptionManager: NostrSubscriptionManager,
    val aliasRegistry: GeohashAliasRegistry,
    val conversationRegistry: GeohashConversationRegistry,
    val powPreferenceManager: PoWPreferenceManager,
    val locationNotesManager: LocationNotesManager,
    val identityBridge: NostrIdentityBridge,
) {
    companion object {
        fun create(config: NostrConfig): NostrClient {
            val deduplicator = config.eventCacheCapacity
                ?.let { NostrEventDeduplicator(it) }
                ?: NostrEventDeduplicator()
            val relayManager = NostrRelayManager(deduplicator, config.http, config.dispatchers)
            val relayDirectory = RelayDirectory(
                settings = config.settings,
                httpClientProvider = config.http,
                storage = config.relayStorage,
                dispatchers = config.dispatchers,
            )
            val subscriptionManager = NostrSubscriptionManager(config.scope, relayDirectory, relayManager)
            return NostrClient(
                relayManager = relayManager,
                relayDirectory = relayDirectory,
                subscriptionManager = subscriptionManager,
                aliasRegistry = GeohashAliasRegistry(config.settings),
                conversationRegistry = GeohashConversationRegistry(config.settings),
                powPreferenceManager = PoWPreferenceManager(config.settings),
                locationNotesManager = LocationNotesManager(config.dispatchers),
                identityBridge = NostrIdentityBridge(config.stateManager),
            )
        }
    }
}
