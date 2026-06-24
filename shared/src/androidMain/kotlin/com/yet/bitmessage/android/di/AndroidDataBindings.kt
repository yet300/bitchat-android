package com.yet.bitmessage.android.di

import android.app.Application
import android.content.Context
import com.app.common.AppDispatchers
import com.app.crypto.EncryptionService
import com.app.crypto.identity.PeerFingerprintManager
import com.app.crypto.identity.SecureIdentityStateManager
import com.app.transport.FavoriteNostrLink
import com.app.transport.GeohashReadReceiptRouter
import com.app.transport.IncomingMessageSink
import com.app.transport.NicknameSource
import com.app.transport.SeenMessageStore
import com.app.transport.mesh.TransferProgressManager
import com.app.transport.meshgraph.MeshGraphService
import com.app.transport.debug.DebugPreferenceManager
import com.app.transport.debug.DebugSettingsManager
import com.app.transport.mesh.BleBearer
import com.app.transport.mesh.FragmentManager
import com.app.transport.mesh.BluetoothMeshService
import com.app.transport.mesh.MeshBearer
import com.app.transport.mesh.MeshLifecycleController
import com.app.transport.nostr.NostrRelayManager
import com.app.transport.mesh.MeshNetwork
import com.app.transport.net.TorPreferenceManager
import com.app.transport.nostr.PoWPreferenceManager
import com.app.transport.notification.ServiceNotifier
import com.app.domain.repository.CameraPermissionRepository
import com.app.domain.repository.ConnectivityRepository
import com.app.domain.repository.DatabaseKeyProvider
import com.app.domain.repository.DatabasePanicWiper
import com.app.database.db.DatabaseManager
import com.app.database.db.DB_FILE_NAME
import com.yet.bitmessage.android.database.AndroidDatabaseKeyProvider
import com.app.common.settings.SettingsStore
import com.app.domain.repository.BatteryOptimizationRepository
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.MediaCleaner
import com.app.domain.repository.MeshResetPort
import com.app.domain.repository.NotificationPermissionRepository
import com.app.domain.repository.PeerVerificationRepository
import com.app.domain.repository.PlaceGeocoder
import com.app.domain.repository.PowRepository
import com.app.domain.repository.TorRepository
import com.app.domain.repository.VerificationRepository
import com.app.transport.features.file.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.yet.bitmessage.android.geohash.AndroidPlaceGeocoder
import com.yet.bitmessage.android.connectivity.AndroidConnectivityRepository
import com.yet.bitmessage.android.connectivity.RuntimePermissionRequester
import com.yet.bitmessage.android.notification.AndroidNotificationPermissionRepository
import com.yet.bitmessage.android.power.AndroidBatteryOptimizationRepository
import com.yet.bitmessage.android.settings.PowRepositoryImpl
import com.yet.bitmessage.android.settings.TorRepositoryImpl
import com.yet.bitmessage.android.verification.AndroidCameraPermissionRepository
import com.yet.bitmessage.android.verification.VerificationCoordinator
import com.yet.bitmessage.android.verification.VerificationRepositoryImpl
import com.app.transport.VerificationService
import com.app.crypto.secure.KSafeSecureKeyValueStore
import com.app.crypto.secure.SecureKeyValueStore
import eu.anifantakis.lib.ksafe.KSafe
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Platform providers for the data layer's infrastructure dependencies (the leaf objects the
 * repository implementations in :core:data depend on). Each one delegates to the existing
 * process-wide singleton / holder so the graph and the still-living legacy paths share a *single*
 * instance during the Strangler-Fig transition — no duplicated mesh / favorites / identity state.
 * When the god-classes dissolve (Phase C) these holder/getInstance shims retire in favour of
 * graph-owned construction.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object AndroidDataBindings {

    // KSafe is single-process and must be one instance per fileName (two live instances on one file
    // diverge). The @SingleIn(AppScope) scope guarantees that here; the file names are unchanged from
    // the previous crypto-layer holder so the on-disk vault/prefs stores keep their identity.
    private const val VAULT_FILE = "bitchat_vault"
    private const val PREFS_FILE = "bitchat_prefs"

    @Provides
    @SingleIn(AppScope::class)
    fun provideApplication(context: Context): Application = context.applicationContext as Application

    /**
     * Single app-wide KSafe **plain** store for all non-secret preferences (theme, toggles,
     * onboarding flags, mesh settings). [SettingsStoreImpl] writes everything here with
     * `KSafeWriteMode.Plain`. Secrets stay in the encrypted vault ([SecureKeyValueStore]), not here.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideKSafePrefs(context: Context): KSafe =
        KSafe(context.applicationContext, fileName = PREFS_FILE)

    /**
     * Encrypted secret store (KSafe AES-256-GCM, AES key in the Android Keystore/TEE) for all secrets
     * at rest — identity/signing/Nostr keys, the SQLCipher passphrase, favorites. The Context-bound
     * KSafe construction lives here at the DI edge so the crypto layer stays commonMain-only.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideSecureKeyValueStore(context: Context): SecureKeyValueStore =
        KSafeSecureKeyValueStore(KSafe(context.applicationContext, fileName = VAULT_FILE))

    @Provides
    @SingleIn(AppScope::class)
    fun provideEncryptionService(
        store: SecureKeyValueStore,
        peerFingerprintManager: PeerFingerprintManager,
    ): EncryptionService = EncryptionService(store, peerFingerprintManager)

    @Provides
    @SingleIn(AppScope::class)
    fun provideSecureIdentityStateManager(store: SecureKeyValueStore): SecureIdentityStateManager =
        SecureIdentityStateManager(store)

    /** Hardware-rooted SQLCipher passphrase (stored via the encrypted secret store, never plaintext). */
    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabaseKeyProvider(secureStore: SecureIdentityStateManager): DatabaseKeyProvider =
        AndroidDatabaseKeyProvider(secureStore)

    /** Fragment reassembly state shared by the BLE stack and the BMS engine. */
    @Provides
    @SingleIn(AppScope::class)
    fun provideFragmentManager(): FragmentManager = FragmentManager()

    /**
     * Graph-owned [BleBearer]. The same instance is multibound into [Set]<[MeshBearer]>
     * (below) and injected into [BluetoothMeshService] — no construction inside BMS.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideBleBearer(
        context: Context,
        encryptionService: EncryptionService,
        debugSettingsManager: DebugSettingsManager,
        fragmentManager: FragmentManager,
        transferProgressManager: TransferProgressManager,
    ): BleBearer = BleBearer(
        context.applicationContext,
        encryptionService.getIdentityFingerprint().take(16),
        debugSettingsManager,
        fragmentManager,
        transferProgressManager,
    )

    @Provides
    @IntoSet
    fun provideBleBearerIntoSet(bleBearer: BleBearer): MeshBearer = bleBearer

    @Provides
    @SingleIn(AppScope::class)
    fun provideBluetoothMeshService(
        context: Context,
        dispatchers: AppDispatchers,
        debugSettingsManager: DebugSettingsManager,
        debugPreferenceManager: DebugPreferenceManager,
        seenMessageStore: SeenMessageStore,
        meshGraphService: MeshGraphService,
        peerFingerprintManager: PeerFingerprintManager,
        encryptionService: EncryptionService,
        serviceNotifier: ServiceNotifier,
        nicknameSource: NicknameSource,
        incomingSink: IncomingMessageSink,
        favoriteNostrLink: FavoriteNostrLink,
        geohashReadReceiptRouter: GeohashReadReceiptRouter,
        verificationService: com.app.transport.VerificationService,
        fragmentManager: FragmentManager,
        bleBearer: BleBearer,
        meshNetwork: MeshNetwork,
    ): BluetoothMeshService = BluetoothMeshService(
        context.applicationContext,
        debugSettingsManager,
        debugPreferenceManager,
        seenMessageStore,
        meshGraphService,
        peerFingerprintManager,
        encryptionService,
        serviceNotifier,
        nicknameSource,
        incomingSink,
        favoriteNostrLink,
        geohashReadReceiptRouter,
        verificationService,
        fragmentManager,
        bleBearer,
        meshNetwork,
        dispatchers = dispatchers,
    )

    /** Narrow lifecycle contract for the foreground service (ISP); same underlying BMS. */
    @Provides
    fun provideMeshLifecycleController(mesh: BluetoothMeshService): MeshLifecycleController = mesh

    /** Domain port for panic-wipe mesh identity rotation — delegates to BMS.reset(). */
    @Provides
    fun provideMeshResetPort(mesh: BluetoothMeshService): MeshResetPort =
        object : MeshResetPort {
            override suspend fun reset() = withContext(Dispatchers.IO) { mesh.reset() }
        }

    /** Domain port for panic-wipe media deletion — delegates to FileUtils.clearAllMedia(). */
    @Provides
    fun provideMediaCleaner(context: Context): MediaCleaner =
        object : MediaCleaner {
            override suspend fun wipeMedia() = withContext(Dispatchers.IO) {
                FileUtils.clearAllMedia(context.applicationContext)
            }
        }

    /**
     * Panic crypto-erase of the encrypted DB: close the handle, destroy the passphrase (its only copy
     * lives behind the hardware-rooted secret store), then drop the file. Destroying the key makes the
     * database permanently undecryptable even if the file survives.
     */
    @Provides
    fun provideDatabasePanicWiper(
        context: Context,
        databaseManager: DatabaseManager,
        keyProvider: DatabaseKeyProvider,
    ): DatabasePanicWiper =
        object : DatabasePanicWiper {
            override suspend fun wipe() = withContext(Dispatchers.IO) {
                databaseManager.close()
                keyProvider.destroyKey()
                context.applicationContext.deleteDatabase(DB_FILE_NAME)
                Unit
            }
        }

    /** Graph-owned holder the Activity attaches its ActivityResult permission launcher into. */
    @Provides
    @SingleIn(AppScope::class)
    fun provideRuntimePermissionRequester(): RuntimePermissionRequester = RuntimePermissionRequester()

    /** Transport-backed settings ports (Tor, PoW) over the existing managers (DIP for the feature). */
    @Provides
    fun provideTorRepository(manager: TorPreferenceManager): TorRepository = TorRepositoryImpl(manager)

    @Provides
    fun providePlaceGeocoder(context: Context, dispatchers: AppDispatchers): PlaceGeocoder =
        AndroidPlaceGeocoder(context.applicationContext, dispatchers)

    @Provides
    fun providePowRepository(manager: PoWPreferenceManager): PowRepository = PowRepositoryImpl(manager)

    @Provides
    @SingleIn(AppScope::class)
    fun provideConnectivityRepository(
        context: Context,
        torPreferenceManager: TorPreferenceManager,
        permissionRequester: RuntimePermissionRequester,
        meshLifecycle: MeshLifecycleController,
        nostrRelayManager: NostrRelayManager,
    ): ConnectivityRepository =
        AndroidConnectivityRepository(
            context.applicationContext,
            torPreferenceManager,
            permissionRequester,
            meshLifecycle,
            nostrRelayManager,
        )

    @Provides
    @SingleIn(AppScope::class)
    fun provideNotificationPermissionRepository(
        context: Context,
        permissionRequester: RuntimePermissionRequester,
    ): NotificationPermissionRepository =
        AndroidNotificationPermissionRepository(context.applicationContext, permissionRequester)

    @Provides
    fun provideVerificationRepository(
        verificationService: VerificationService,
        identityRepository: IdentityRepository,
    ): VerificationRepository =
        VerificationRepositoryImpl(verificationService, identityRepository)

    /** The graph-owned coordinator is the [PeerVerificationRepository] and the BMS verify listener. */
    @Provides
    fun providePeerVerificationRepository(coordinator: VerificationCoordinator): PeerVerificationRepository =
        coordinator

    @Provides
    @SingleIn(AppScope::class)
    fun provideCameraPermissionRepository(
        context: Context,
        permissionRequester: RuntimePermissionRequester,
    ): CameraPermissionRepository =
        AndroidCameraPermissionRepository(context.applicationContext, permissionRequester)

    @Provides
    @SingleIn(AppScope::class)
    fun provideBatteryOptimizationRepository(
        context: Context,
        settingsStore: SettingsStore,
    ): BatteryOptimizationRepository =
        AndroidBatteryOptimizationRepository(context.applicationContext, settingsStore)
}
