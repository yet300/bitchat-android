package com.app.data.di

import com.app.crypto.secure.KSafeSecureKeyValueStore
import com.app.crypto.secure.SecureKeyValueStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import eu.anifantakis.lib.ksafe.KSafe

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
object NativeDataBindings {

    /**
     * Single app-wide KSafe **plain** store for all non-secret preferences (`SettingsStore` writes
     * with `KSafeWriteMode.Plain`). Secrets stay in the encrypted vault, not here.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideKSafePrefs(): KSafe = KSafe(fileName = DataBindings.PREFS_FILE)

    /**
     * Encrypted secret store (KSafe AES-256-GCM, key in the Keychain/Secure Enclave) for all secrets
     * at rest — identity/signing/Nostr keys, the SQLCipher passphrase, favorites.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideSecureKeyValueStore(): SecureKeyValueStore =
        KSafeSecureKeyValueStore(KSafe(fileName = DataBindings.VAULT_FILE))
}
