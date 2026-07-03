package com.app.data.di

import android.content.Context
import com.app.common.permission.PermissionController
import com.app.common.settings.SettingsStore
import com.app.crypto.EncryptionService
import com.app.crypto.secure.KSafeSecureKeyValueStore
import com.app.crypto.secure.SecureKeyValueStore
import com.app.data.app.AndroidAppForegroundState
import com.app.data.connectivity.AndroidConnectivityRepository
import com.app.data.power.AndroidBatteryOptimizationRepository
import com.app.database.db.DB_FILE_NAME
import com.app.database.db.DatabaseManager
import com.app.domain.app.AppForegroundState
import com.app.domain.repository.BatteryOptimizationRepository
import com.app.domain.repository.ConnectivityRepository
import com.app.domain.repository.DatabaseKeyProvider
import com.app.domain.repository.DatabasePanicWiper
import com.app.domain.repository.MediaCleaner
import com.app.transport.debug.DebugSettingsManager
import com.app.transport.features.file.AndroidIncomingFileStore
import com.app.transport.features.file.FileUtils
import com.app.transport.features.file.IncomingFileStore
import com.app.transport.mesh.BleBearer
import com.app.transport.mesh.FragmentManager
import com.app.transport.mesh.MeshBearer
import com.app.transport.mesh.MeshLifecycleController
import com.app.transport.mesh.TransferProgressManager
import com.app.transport.mesh.createAndroidBleBearer
import com.app.transport.net.TorPreferenceManager
import com.app.transport.nostr.NostrRelayManager
import eu.anifantakis.lib.ksafe.KSafe
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Platform providers for the data layer's infrastructure dependencies (the leaf objects the
 * repository implementations depend on) — moved here from :shared androidMain. :core:data is the
 * bridge layer that depends on both :core:domain (the ports) and :core:transport/:core:crypto/
 * :core:database (the impls), so these domain-port-over-transport wirings live here rather than in
 * :shared or in :core:transport (which cannot see :core:domain).
 *
 * Each leaf delegates to the graph-owned process-wide singleton so the graph and the still-living
 * legacy paths share a *single* instance during the Strangler-Fig transition. `Context` is supplied
 * by the application graph factory.
 *
 * Constructor-injectable impls are bound via `@Binds`; the leaves that need a `Context` or bespoke
 * construction stay `@Provides` in the [companion]. This is the single androidMain data binding
 * container; the platform-free mesh coordinator wiring lives in [DataBindings].
 */
@ContributesTo(AppScope::class)
@BindingContainer
abstract class DataAndroidBindings {

    /** Process foreground/background signal (ProcessLifecycleOwner) for data-saving throttling. */
    @Binds
    abstract val AndroidAppForegroundState.bindAppForegroundState: AppForegroundState

    companion object {

        /**
         * The `IncomingFileStore` seam (transport's commonMain port) used by `NostrDirectMessageIngest`
         * to persist incoming Nostr file transfers without holding an Android `Context` itself.
         */
        @Provides
        @SingleIn(AppScope::class)
        fun provideIncomingFileStore(context: Context): IncomingFileStore =
            AndroidIncomingFileStore(context.applicationContext)

        /**
         * Single app-wide KSafe **plain** store for all non-secret preferences (theme, toggles,
         * onboarding flags, mesh settings). [SettingsStore] writes everything here with
         * `KSafeWriteMode.Plain`. Secrets stay in the encrypted vault ([SecureKeyValueStore]), not here.
         */
        @Provides
        @SingleIn(AppScope::class)
        fun provideKSafePrefs(context: Context): KSafe =
            KSafe(context.applicationContext, fileName = DataBindings.PREFS_FILE)

        /**
         * Encrypted secret store (KSafe AES-256-GCM, AES key in the Android Keystore/TEE) for all secrets
         * at rest — identity/signing/Nostr keys, the SQLCipher passphrase, favorites. The Context-bound
         * KSafe construction lives here at the DI edge so the crypto layer stays commonMain-only.
         */
        @Provides
        @SingleIn(AppScope::class)
        fun provideSecureKeyValueStore(context: Context): SecureKeyValueStore =
            KSafeSecureKeyValueStore(KSafe(context.applicationContext, fileName = DataBindings.VAULT_FILE))

        /**
         * Graph-owned [BleBearer]. The same instance is multibound into [Set]<[MeshBearer]>
         * (below) and injected into the shared `MeshCoordinator` — no construction inside it.
         */
        @Provides
        @SingleIn(AppScope::class)
        fun provideBleBearer(
            context: Context,
            encryptionService: EncryptionService,
            debugSettingsManager: DebugSettingsManager,
            fragmentManager: FragmentManager,
            transferProgressManager: TransferProgressManager,
        ): BleBearer = createAndroidBleBearer(
            context = context.applicationContext,
            myPeerID = encryptionService.getIdentityFingerprint().take(16),
            debugSettingsManager = debugSettingsManager,
            fragmentManager = fragmentManager,
            transferProgressManager = transferProgressManager,
        )

        @Provides
        @IntoSet
        fun provideBleBearerIntoSet(bleBearer: BleBearer): MeshBearer = bleBearer

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

        @Provides
        @SingleIn(AppScope::class)
        fun provideConnectivityRepository(
            context: Context,
            torPreferenceManager: TorPreferenceManager,
            permissionController: PermissionController,
            meshLifecycle: MeshLifecycleController,
            nostrRelayManager: NostrRelayManager,
        ): ConnectivityRepository =
            AndroidConnectivityRepository(
                context.applicationContext,
                torPreferenceManager,
                permissionController,
                meshLifecycle,
                nostrRelayManager,
            )

        @Provides
        @SingleIn(AppScope::class)
        fun provideBatteryOptimizationRepository(
            context: Context,
            settingsStore: SettingsStore,
        ): BatteryOptimizationRepository =
            AndroidBatteryOptimizationRepository(context.applicationContext, settingsStore)
    }
}
