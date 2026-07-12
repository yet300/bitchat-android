package com.app.transport.di

import com.app.common.AppDispatchers
import com.app.crypto.EncryptionService
import com.app.crypto.identity.PeerFingerprintManager
import com.app.crypto.identity.SecureIdentityStateManager
import com.app.transport.FavoriteNostrLink
import com.app.transport.GeohashReadReceiptRouter
import com.app.transport.IncomingMessageSink
import com.app.transport.MeshBearerBuilder
import com.app.transport.MeshCallbacks
import com.app.transport.MeshStores
import com.app.transport.MeshTelemetry
import com.app.transport.MeshTransport
import com.app.transport.MeshTransportConfig
import com.app.transport.NicknameSource
import com.app.transport.NostrClient
import com.app.transport.NostrConfig
import com.app.transport.SeenMessageStore
import com.app.transport.TorClient
import com.app.transport.TorConfig
import com.app.transport.VerificationService
import com.app.transport.debug.DebugConfigStore
import com.app.transport.debug.DebugPreferenceManager
import com.app.transport.debug.DebugSettingsManager
import com.app.transport.features.file.IncomingFileStore
import com.app.transport.mesh.MeshLifecycleController
import com.app.transport.mesh.MeshNetwork
import com.app.transport.mesh.MeshService
import com.app.transport.mesh.GatewayConfigStore
import com.app.transport.meshgraph.MeshGraphService
import com.app.transport.net.ArtiTorManager
import com.app.transport.net.TorConfigStore
import com.app.transport.net.HttpClientProvider
import com.app.transport.net.TorDataDirProvider
import com.app.transport.net.TorPreferenceManager
import com.app.transport.net.WebSocketClientProvider
import com.app.transport.notification.ServiceNotifier
import com.app.transport.nostr.GeohashAliasRegistry
import com.app.transport.nostr.GeohashConversationRegistry
import com.app.transport.nostr.LocationNotesManager
import com.app.transport.nostr.NostrConfigStore
import com.app.transport.nostr.NostrIdentityBridge
import com.app.transport.nostr.NostrRelayManager
import com.app.transport.nostr.NostrSubscriptionManager
import com.app.transport.nostr.PoWPreferenceManager
import com.app.transport.nostr.RelayDirectory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.engine.HttpClientEngineFactory
import kotlinx.coroutines.CoroutineScope

/**
 * Optional Metro glue for apps that use a `@DependencyGraph`: binds the DI-agnostic SDK factories
 * ([TorClient], [NostrClient], [MeshTransport]) into an [AppScope] graph and re-exposes the objects
 * the graph previously constructed by hand. Apps that do not use Metro simply do not include this
 * module and call the factories directly.
 *
 * The consumer graph must still provide the platform/app seams these factories need (the config
 * store ports, the crypto services, the app callbacks/stores, the ktor engine, relay storage, the
 * Tor data dir, the app coroutine scope and the [MeshBearerBuilder]).
 */
@ContributesTo(AppScope::class)
@BindingContainer
object TransportFactoryBindings {

    // ---- Tor ----

    @Provides
    @SingleIn(AppScope::class)
    fun provideTorClient(
        configStore: TorConfigStore,
        dataDir: TorDataDirProvider,
        engineFactory: HttpClientEngineFactory<*>,
        dispatchers: AppDispatchers,
    ): TorClient = TorClient.create(TorConfig(configStore, dataDir, engineFactory, dispatchers))

    @Provides
    fun provideArtiTorManager(tor: TorClient): ArtiTorManager = tor.torManager

    @Provides
    fun provideHttpClientProvider(tor: TorClient): HttpClientProvider = tor.httpClientProvider

    @Provides
    fun provideWebSocketClientProvider(tor: TorClient): WebSocketClientProvider =
        tor.webSocketClientProvider

    @Provides
    fun provideTorPreferenceManager(tor: TorClient): TorPreferenceManager = tor.torPreferenceManager

    // ---- Nostr ----

    @Provides
    @SingleIn(AppScope::class)
    fun provideNostrClient(
        configStore: NostrConfigStore,
        http: HttpClientProvider,
        relayStorage: com.app.transport.nostr.RelayDirectoryStorage,
        stateManager: SecureIdentityStateManager,
        scope: CoroutineScope,
        dispatchers: AppDispatchers,
    ): NostrClient = NostrClient.create(
        NostrConfig(
            configStore = configStore,
            http = http,
            relayStorage = relayStorage,
            stateManager = stateManager,
            scope = scope,
            dispatchers = dispatchers,
        ),
    )

    @Provides
    fun provideNostrRelayManager(nostr: NostrClient): NostrRelayManager = nostr.relayManager

    @Provides
    fun provideRelayDirectory(nostr: NostrClient): RelayDirectory = nostr.relayDirectory

    @Provides
    fun provideNostrSubscriptionManager(nostr: NostrClient): NostrSubscriptionManager =
        nostr.subscriptionManager

    @Provides
    fun provideGeohashAliasRegistry(nostr: NostrClient): GeohashAliasRegistry = nostr.aliasRegistry

    @Provides
    fun provideGeohashConversationRegistry(nostr: NostrClient): GeohashConversationRegistry =
        nostr.conversationRegistry

    @Provides
    fun providePoWPreferenceManager(nostr: NostrClient): PoWPreferenceManager =
        nostr.powPreferenceManager

    @Provides
    fun provideLocationNotesManager(nostr: NostrClient): LocationNotesManager =
        nostr.locationNotesManager

    @Provides
    fun provideNostrIdentityBridge(nostr: NostrClient): NostrIdentityBridge = nostr.identityBridge

    // ---- Mesh seam leaves (formerly @Inject in the SDK) ----

    @Provides
    @SingleIn(AppScope::class)
    fun provideDebugPreferenceManager(store: DebugConfigStore): DebugPreferenceManager =
        DebugPreferenceManager(store)

    @Provides
    @SingleIn(AppScope::class)
    fun provideDebugSettingsManager(prefs: DebugPreferenceManager): DebugSettingsManager =
        DebugSettingsManager(prefs)

    @Provides
    fun provideMeshTelemetry(impl: DebugSettingsManager): MeshTelemetry = impl

    @Provides
    @SingleIn(AppScope::class)
    fun provideSeenMessageStore(
        secure: SecureIdentityStateManager,
        dispatchers: AppDispatchers,
    ): SeenMessageStore = SeenMessageStore(secure, dispatchers)

    @Provides
    @SingleIn(AppScope::class)
    fun provideVerificationService(encryptionService: EncryptionService): VerificationService =
        VerificationService(encryptionService)

    @Provides
    @SingleIn(AppScope::class)
    fun provideMeshGraphService(): MeshGraphService = MeshGraphService()

    // ---- Mesh ----

    @Provides
    @SingleIn(AppScope::class)
    fun provideMeshTransport(
        encryption: EncryptionService,
        fingerprints: PeerFingerprintManager,
        incomingSink: IncomingMessageSink,
        nicknameSource: NicknameSource,
        favoriteNostrLink: FavoriteNostrLink,
        readReceiptRouter: GeohashReadReceiptRouter,
        verificationService: VerificationService,
        serviceNotifier: ServiceNotifier,
        seenMessageStore: SeenMessageStore,
        incomingFileStore: IncomingFileStore,
        telemetry: MeshTelemetry,
        debugPreferenceManager: DebugPreferenceManager,
        meshGraphService: MeshGraphService,
        gatewayConfigStore: GatewayConfigStore,
        bearerBuilder: MeshBearerBuilder,
        dispatchers: AppDispatchers,
    ): MeshTransport = MeshTransport.create(
        MeshTransportConfig(
            encryption = encryption,
            fingerprints = fingerprints,
            callbacks = MeshCallbacks(
                incomingSink = incomingSink,
                nicknameSource = nicknameSource,
                favoriteNostrLink = favoriteNostrLink,
                readReceiptRouter = readReceiptRouter,
                verificationService = verificationService,
                serviceNotifier = serviceNotifier,
            ),
            stores = MeshStores(
                seenMessageStore = seenMessageStore,
                incomingFileStore = incomingFileStore,
                telemetry = telemetry,
                debugPreferenceManager = debugPreferenceManager,
            ),
            bearers = bearerBuilder::build,
            meshGraphService = meshGraphService,
            gatewayConfigStore = gatewayConfigStore,
            dispatchers = dispatchers,
        ),
    )

    @Provides
    fun provideMeshService(transport: MeshTransport): MeshService = transport.service

    @Provides
    fun provideMeshLifecycleController(transport: MeshTransport): MeshLifecycleController =
        transport.lifecycle

    @Provides
    fun provideMeshNetwork(transport: MeshTransport): MeshNetwork = transport.meshNetwork

    @Provides
    fun provideTransferProgressManager(
        transport: MeshTransport,
    ): com.app.transport.mesh.TransferProgressManager = transport.transferProgressManager
}
