package com.bitchat.android.di

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
import com.app.transport.mesh.MeshNetwork
import com.app.transport.net.TorPreferenceManager
import com.app.transport.notification.ServiceNotifier
import com.app.domain.repository.ConnectivityRepository
import com.bitchat.android.connectivity.AndroidConnectivityRepository
import com.bitchat.android.connectivity.RuntimePermissionRequester
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.SharedPreferencesSettings
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

    @Provides
    @SingleIn(AppScope::class)
    fun provideApplication(context: Context): Application = context.applicationContext as Application

    /**
     * Single app-wide [ObservableSettings] for all non-secret preferences (one store,
     * namespaced keys, never enumerate keys). Secrets stay in the Tink-backed
     * [com.app.crypto.secure.SecureKeyValueStore], not here.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideObservableSettings(context: Context): ObservableSettings =
        SharedPreferencesSettings(context.getSharedPreferences("bitchat", Context.MODE_PRIVATE))

    @Provides
    @SingleIn(AppScope::class)
    fun provideEncryptionService(context: Context, peerFingerprintManager: PeerFingerprintManager): EncryptionService = EncryptionService(context, peerFingerprintManager)

    @Provides
    @SingleIn(AppScope::class)
    fun provideSecureIdentityStateManager(context: Context): SecureIdentityStateManager =
        SecureIdentityStateManager(context)

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

    /** Graph-owned holder the Activity attaches its ActivityResult permission launcher into. */
    @Provides
    @SingleIn(AppScope::class)
    fun provideRuntimePermissionRequester(): RuntimePermissionRequester = RuntimePermissionRequester()

    @Provides
    @SingleIn(AppScope::class)
    fun provideConnectivityRepository(
        context: Context,
        torPreferenceManager: TorPreferenceManager,
        permissionRequester: RuntimePermissionRequester,
    ): ConnectivityRepository =
        AndroidConnectivityRepository(context.applicationContext, torPreferenceManager, permissionRequester)
}
