package com.app.data.di

import com.app.common.AppDispatchers
import com.app.crypto.secure.KSafeSecureKeyValueStore
import com.app.crypto.secure.SecureKeyValueStore
import com.app.data.app.NativeAppForegroundState
import com.app.data.file.NativeMediaCleaner
import com.app.database.db.DatabaseManager
import com.app.database.db.deleteNativeDatabaseFile
import com.app.domain.app.AppForegroundState
import com.app.domain.repository.DatabaseKeyProvider
import com.app.domain.repository.DatabasePanicWiper
import com.app.domain.repository.MediaCleaner
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import eu.anifantakis.lib.ksafe.KSafe
import kotlinx.coroutines.withContext

/**
 * Native (Apple) counterpart of `DataAndroidBindings` for the data layer's platform-constructed
 * leaves. On Apple targets KSafe needs no Context — its factory roots encryption keys in the
 * Keychain/Secure Enclave (`…ThisDeviceOnly`), so both stores stay encrypted at rest and
 * fail closed, matching the Android Keystore-backed setup. The platform-free providers
 * (EncryptionService, SecureIdentityStateManager, FragmentManager) live in [DataBindings];
 * the mesh coordinator cluster (BluetoothMeshService et al.) is Android-only and its iOS
 * counterpart is app-layer work.
 */
@ContributesTo(AppScope::class)
@BindingContainer
abstract class NativeDataBindings {

    /** Process foreground/background signal (UIKit lifecycle) for data-saving throttling. */
    @Binds
    abstract val NativeAppForegroundState.bindAppForegroundState: AppForegroundState

    companion object {

        /**
         * Single app-wide KSafe **plain** store for all non-secret preferences (`SettingsStore` writes
         * with `KSafeWriteMode.Plain`). Secrets stay in the encrypted vault, not here.
         */
        @Provides
        @SingleIn(AppScope::class)
        fun provideKSafePrefs(): KSafe = KSafe(fileName = DataBindings.PREFS_FILE)

        /**
         * Encrypted secret store (KSafe AES-256-GCM, key in the Keychain/Secure Enclave) for all
         * secrets at rest — identity/signing/Nostr keys, the SQLCipher passphrase, favorites.
         */
        @Provides
        @SingleIn(AppScope::class)
        fun provideSecureKeyValueStore(): SecureKeyValueStore =
            KSafeSecureKeyValueStore(KSafe(fileName = DataBindings.VAULT_FILE))

        /**
         * Panic crypto-erase of the DB: close the handle, destroy the passphrase (its only copy lives
         * behind the Keychain-rooted secret store), then drop the file via SQLiter's own path
         * resolution. NOTE: until SQLCipher is wired on native the file itself is plaintext, so the
         * deletion — not the key destruction — is what removes the data on iOS.
         */
        @Provides
        fun provideDatabasePanicWiper(
            databaseManager: DatabaseManager,
            keyProvider: DatabaseKeyProvider,
            dispatchers: AppDispatchers,
        ): DatabasePanicWiper =
            object : DatabasePanicWiper {
                override suspend fun wipe() = withContext(dispatchers.io) {
                    databaseManager.close()
                    keyProvider.destroyKey()
                    deleteNativeDatabaseFile()
                }
            }

        /** Domain port for panic-wipe media deletion — clears the incoming-media caches dirs. */
        @Provides
        fun provideMediaCleaner(dispatchers: AppDispatchers): MediaCleaner =
            NativeMediaCleaner(dispatchers)
    }
}
