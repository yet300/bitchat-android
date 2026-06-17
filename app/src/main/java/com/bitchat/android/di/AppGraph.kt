package com.bitchat.android.di

import com.app.domain.repository.ChannelRepository
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.ConversationRepository
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.MessageRepository
import com.app.domain.repository.MessageTransport
import com.app.domain.repository.NotificationMutePolicy
import com.app.domain.repository.PeerRepository
import com.app.domain.repository.PeerVerificationRepository
import com.app.domain.repository.SearchRepository
import com.app.domain.repository.SettingsRepository
import com.app.crypto.identity.PeerFingerprintManager
import com.app.data.AppStateStore
import com.app.data.favorites.FavoritesPersistenceService
import com.app.data.routing.MessageRouter
import com.app.data.routing.PeerAddressResolver
import com.app.transport.SeenMessageStore
import com.app.transport.VerificationService
import com.app.transport.mesh.BluetoothMeshService
import com.app.transport.mesh.MeshLifecycleController
import com.app.transport.mesh.TransferProgressManager
import com.app.transport.meshgraph.MeshGraphService
import com.app.transport.debug.DebugSettingsManager
import com.app.transport.nostr.GeohashAliasRegistry
import com.app.transport.nostr.GeohashConversationRegistry
import com.app.transport.net.ArtiTorManager
import com.app.transport.net.HttpClientProvider
import com.app.transport.net.TorPreferenceManager
import com.app.transport.nostr.LocationNotesManager
import com.app.transport.nostr.NostrIdentityBridge
import com.app.transport.nostr.NostrRelayManager
import com.app.transport.nostr.PoWPreferenceManager
import com.app.transport.nostr.RelayDirectory
import com.app.data.nostr.NostrMessageSender
import com.yet.bitmessage.android.connectivity.RuntimePermissionRequester
import com.bitchat.android.geohash.GeohashBookmarksStore
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.onboarding.BackgroundLocationPreferenceManager
import com.bitchat.android.onboarding.BatteryOptimizationPreferenceManager
import com.bitchat.android.service.MeshServicePreferences
import com.bitchat.android.ui.theme.ThemePreferenceManager
import com.russhwolf.settings.ObservableSettings
import com.yet.bitmessage.di.AppGraph as SharedAppGraph

/**
 * Public API of the application dependency graph: the domain ports the app (and, in Phase C, the
 * Decompose component tree) resolves. The concrete graph is generated per platform
 * ([AndroidAppGraph]). Repository implementations stay internal to :core:data — only these domain
 * interfaces cross the module boundary (DIP).
 *
 * Extends the :shared [SharedAppGraph] contract so the Phase C Decompose entry point
 * ([com.bitchat.android.BitMessageActivity]) resolves `rootFactory` through the same single
 * graph. When the concrete graph relocates into :shared, this :app interface and its legacy
 * accessors retire.
 */
interface AppGraph : SharedAppGraph {
    val settingsRepository: SettingsRepository
    val contactRepository: ContactRepository
    val channelRepository: ChannelRepository
    val messageRepository: MessageRepository
    val conversationRepository: ConversationRepository
    val peerRepository: PeerRepository
    val searchRepository: SearchRepository
    val identityRepository: IdentityRepository
    val messageTransport: MessageTransport

    // Temporary Phase-D bridges: transport prefs/registries migrated onto SettingsStore are exposed
    // here so the not-yet-graph :app consumers (god-classes, Composables) can resolve the single
    // graph-owned instance. These accessors retire as the consumers dissolve in Phase C.
    val geohashConversationRegistry: GeohashConversationRegistry
    val geohashAliasRegistry: GeohashAliasRegistry
    val debugSettingsManager: DebugSettingsManager
    val powPreferenceManager: PoWPreferenceManager
    val relayDirectory: RelayDirectory
    val torPreferenceManager: TorPreferenceManager
    val appStateStore: AppStateStore
    // temporary Phase-D/DI-core bridge, retires Phase C
    val seenMessageStore: SeenMessageStore
    val transferProgressManager: TransferProgressManager
    val meshGraphService: MeshGraphService
    val nostrRelayManager: NostrRelayManager
    val nostrIdentityBridge: NostrIdentityBridge
    val nostrMessageSender: NostrMessageSender
    val artiTorManager: ArtiTorManager
    val httpClientProvider: HttpClientProvider
    val locationNotesManager: LocationNotesManager
    // temporary Phase-D/DI-core bridge, retires Phase C
    val peerFingerprintManager: PeerFingerprintManager
    val messageRouter: MessageRouter
    val peerAddressResolver: PeerAddressResolver
    val favoritesPersistenceService: FavoritesPersistenceService

    // Settings-backed preference managers (formerly `object`s over the global appSettings()).
    // Transitional accessors for framework entry points; dissolve into feature stores in Phase C.
    val observableSettings: ObservableSettings
    val themePreferenceManager: ThemePreferenceManager
    val batteryOptimizationPreferenceManager: BatteryOptimizationPreferenceManager
    val backgroundLocationPreferenceManager: BackgroundLocationPreferenceManager
    val meshServicePreferences: MeshServicePreferences
    val locationChannelManager: LocationChannelManager
    val geohashBookmarksStore: GeohashBookmarksStore
    val verificationService: VerificationService
    val notificationMutePolicy: NotificationMutePolicy

    // Graph-owned mesh engine: full surface for the still-living god-classes
    // (MainActivity/ChatViewModel, retires Phase C) and the narrow lifecycle contract
    // for the foreground service (invariant: the service owns the mesh lifecycle).
    val bluetoothMeshService: BluetoothMeshService
    val meshLifecycleController: MeshLifecycleController

    /** Bridge the Phase C Activity attaches its runtime-permission launcher into. */
    val runtimePermissionRequester: RuntimePermissionRequester

    /**
     * Graph-owned QR-verification coordinator. Exposed so the Phase C entry can resolve it eagerly,
     * forcing it to attach as the BMS verify listener (so inbound challenges are answered even
     * before the user opens the scanner).
     */
    val peerVerificationRepository: PeerVerificationRepository
}
