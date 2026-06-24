package com.yet.bitmessage.android.database

import com.app.common.encoding.dataFromHexString
import com.app.common.encoding.hexEncodedString
import com.app.crypto.identity.SecureIdentityStateManager
import com.app.domain.repository.DatabaseKeyProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * Hardware-rooted SQLCipher passphrase. The passphrase is a 32-byte CSPRNG value, persisted through
 * the app's encrypted secret store ([SecureIdentityStateManager] → KSafe vault: AES-256-GCM with the
 * AES key in the Android Keystore/TEE — the same store that guards the Noise identity). It is
 * therefore never written in plaintext and never lands in the plain KSafe prefs.
 *
 * First run generates and stores it; subsequent runs read it back. Crypto-erase ([destroyKey]) drops
 * the only stored copy, after which the encrypted database can no longer be opened.
 *
 * Android-only for now (the secret store is Android-backed); the iOS provider (Keychain-rooted) is a
 * follow-up alongside the iOS SQLCipher driver. Lives in :shared androidMain like the other platform
 * providers so :core:database carries no key-management dependency.
 */
class AndroidDatabaseKeyProvider(
    private val secureStore: SecureIdentityStateManager,
) : DatabaseKeyProvider {

    override suspend fun passphrase(): ByteArray = withContext(Dispatchers.IO) {
        val hex = secureStore.getSecureValue(KEY_DB_PASSPHRASE) ?: generateAndStore()
        hex.dataFromHexString() ?: error("Stored database passphrase is corrupt")
    }

    override suspend fun destroyKey() {
        withContext(Dispatchers.IO) {
            // Crypto-erase: drop the only copy of the passphrase; the SQLCipher DB becomes unopenable.
            secureStore.removeSecureValue(KEY_DB_PASSPHRASE)
        }
    }

    private fun generateAndStore(): String {
        val bytes = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        val hex = bytes.hexEncodedString()
        secureStore.storeSecureValue(KEY_DB_PASSPHRASE, hex)
        return hex
    }

    private companion object {
        const val KEY_DB_PASSPHRASE = "db_passphrase"
        const val PASSPHRASE_BYTES = 32
    }
}
