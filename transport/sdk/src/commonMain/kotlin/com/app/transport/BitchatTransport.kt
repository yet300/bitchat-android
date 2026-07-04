package com.app.transport

import com.app.common.AppDispatchers
import com.app.common.settings.SettingsStore
import com.app.crypto.EncryptionService
import com.app.crypto.identity.PeerFingerprintManager
import com.app.crypto.identity.SecureIdentityStateManager
import com.app.transport.net.TorDataDirProvider
import com.app.transport.nostr.RelayDirectoryStorage
import com.app.transport.meshgraph.MeshGraphService
import io.ktor.client.engine.HttpClientEngineFactory
import kotlinx.coroutines.CoroutineScope

/**
 * One-call configuration for the whole bitchat transport SDK (Tor + Nostr + mesh). Groups every
 * seam the three stacks need; only [dispatchers] and [meshGraphService] have defaults.
 *
 * The facade wires the stacks in dependency order — Tor first, then Nostr over the Tor-aware HTTP
 * client, then the mesh — so consumers never have to thread `tor.httpClientProvider` through by
 * hand. For finer control, build [TorClient]/[NostrClient]/[MeshTransport] directly instead.
 *
 * Example (Android):
 * ```
 * val transport = BitchatTransport.create(
 *     BitchatTransportConfig(
 *         settings = appSettings,
 *         encryption = encryptionService,
 *         fingerprints = peerFingerprintManager,
 *         stateManager = secureIdentityStateManager,
 *         scope = appScope,
 *         torDataDir = { File(context.filesDir, "arti").apply { mkdirs() }.absolutePath },
 *         httpEngineFactory = androidHttpClientEngineFactory(),
 *         relayStorage = AndroidRelayDirectoryStorage(context),
 *         callbacks = meshCallbacks,
 *         stores = meshStores,
 *         bearers = { setOf(createAndroidBleBearer(context, it.myPeerID, it.telemetry, it.fragmentManager, it.transferProgressManager)).let { b -> MeshBearers(b.first()) } },
 *     ),
 * )
 * ```
 */
class BitchatTransportConfig(
    // shared
    val settings: SettingsStore,
    val encryption: EncryptionService,
    val fingerprints: PeerFingerprintManager,
    val stateManager: SecureIdentityStateManager,
    val scope: CoroutineScope,
    // tor
    val torDataDir: TorDataDirProvider,
    val httpEngineFactory: HttpClientEngineFactory<*>,
    // nostr
    val relayStorage: RelayDirectoryStorage,
    // mesh
    val callbacks: MeshCallbacks,
    val stores: MeshStores,
    val bearers: (MeshBearerScope) -> MeshBearers,
    val meshGraphService: MeshGraphService = MeshGraphService(),
    val dispatchers: AppDispatchers = AppDispatchers(),
)

/**
 * The assembled transport SDK: the [tor], [nostr] and [mesh] clients, wired together. Hold this at
 * the application scope; each sub-client exposes its own managers for advanced use.
 */
class BitchatTransport private constructor(
    val tor: TorClient,
    val nostr: NostrClient,
    val mesh: MeshTransport,
) {
    companion object {
        fun create(config: BitchatTransportConfig): BitchatTransport {
            val tor = TorClient.create(
                TorConfig(
                    settings = config.settings,
                    dataDir = config.torDataDir,
                    engineFactory = config.httpEngineFactory,
                    dispatchers = config.dispatchers,
                ),
            )
            val nostr = NostrClient.create(
                NostrConfig(
                    settings = config.settings,
                    http = tor.httpClientProvider,
                    relayStorage = config.relayStorage,
                    stateManager = config.stateManager,
                    scope = config.scope,
                    dispatchers = config.dispatchers,
                ),
            )
            val mesh = MeshTransport.create(
                MeshTransportConfig(
                    encryption = config.encryption,
                    fingerprints = config.fingerprints,
                    callbacks = config.callbacks,
                    stores = config.stores,
                    bearers = config.bearers,
                    meshGraphService = config.meshGraphService,
                    dispatchers = config.dispatchers,
                ),
            )
            return BitchatTransport(tor, nostr, mesh)
        }
    }
}
