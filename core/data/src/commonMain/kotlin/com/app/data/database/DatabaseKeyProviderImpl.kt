package com.app.data.database

import com.app.common.AppDispatchers
import com.app.common.encoding.dataFromHexString
import com.app.common.encoding.hexEncodedString
import com.app.crypto.identity.SecureIdentityStateManager
import com.app.domain.repository.DatabaseKeyProvider
import dev.whyoleg.cryptography.random.CryptographyRandom
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.withContext

/**
 * Hardware-rooted SQLCipher passphrase. The passphrase is a 32-byte CSPRNG value, persisted through
 * the app's encrypted secret store ([SecureIdentityStateManager] → KSafe vault: AES-256-GCM with the
 * AES key in the Android Keystore/TEE — the same store that guards the Noise identity). It is
 * therefore never written in plaintext and never lands in the plain KSafe prefs.
 *
 * First run generates and stores it; subsequent runs read it back. Crypto-erase ([destroyKey]) drops
 * the only stored copy, after which the encrypted database can no longer be opened.
 *
 * Platform-free (commonMain): the CSPRNG is [CryptographyRandom] and the off-main hop uses the
 * injected [AppDispatchers] (`dispatchers.io`), so no JVM types leak in. Only the secret-store backing
 * is provided per-platform (KSafe: Android Keystore / iOS Keychain-Secure-Enclave).
 */
@SingleIn(AppScope::class)
@Inject
class DatabaseKeyProviderImpl(
    private val secureStore: SecureIdentityStateManager,
    private val dispatchers: AppDispatchers
) : DatabaseKeyProvider {

    override suspend fun passphrase(): ByteArray = withContext(dispatchers.io) {
        val hex = secureStore.getSecureValue(KEY_DB_PASSPHRASE) ?: generateAndStore()
        hex.dataFromHexString() ?: error("Stored database passphrase is corrupt")
    }

    override suspend fun destroyKey() {
        withContext(dispatchers.io) {
            // Crypto-erase: drop the only copy of the passphrase; the SQLCipher DB becomes unopenable.
            secureStore.removeSecureValue(KEY_DB_PASSPHRASE)
        }
    }

    private fun generateAndStore(): String {
        val bytes = ByteArray(PASSPHRASE_BYTES).also { CryptographyRandom.nextBytes(it) }
        val hex = bytes.hexEncodedString()
        secureStore.storeSecureValue(KEY_DB_PASSPHRASE, hex)
        return hex
    }

    private companion object {
        const val KEY_DB_PASSPHRASE = "db_passphrase"
        const val PASSPHRASE_BYTES = 32
    }
}
