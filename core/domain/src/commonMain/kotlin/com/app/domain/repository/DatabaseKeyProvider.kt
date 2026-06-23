package com.app.domain.repository

/**
 * Root of trust for the encrypted messenger database (SQLCipher). The passphrase is persisted only
 * through the app's hardware-rooted encrypted secret store (Android: Tink AEAD + an Android Keystore
 * master key — the same store that guards the Noise identity). It is NEVER stored in plaintext and
 * never in the unencrypted multiplatform-settings.
 *
 * Implemented per platform alongside the other `Android*` providers (in :shared androidMain), so the
 * :core:database driver factory stays free of any key-management dependency. The iOS provider
 * (Keychain-rooted) is a follow-up alongside the iOS SQLCipher driver.
 */
interface DatabaseKeyProvider {

    /**
     * The SQLCipher passphrase. First run generates a CSPRNG passphrase and stores it in the encrypted
     * secret store; subsequent runs read it back. The returned array is the live key material —
     * callers must hand it straight to the driver and not retain it.
     */
    suspend fun passphrase(): ByteArray

    /**
     * Crypto-erase: drop the only stored (encrypted) copy of the passphrase, after which the database
     * can no longer be opened. Used by the panic wipe.
     */
    suspend fun destroyKey()
}
