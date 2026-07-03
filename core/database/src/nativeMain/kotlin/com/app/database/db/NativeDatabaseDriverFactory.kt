package com.app.database.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import com.app.common.encoding.hexEncodedString
import com.app.database.BitMessageDatabase
import com.app.domain.repository.DatabaseKeyProvider

/**
 * SQLCipher-encrypted iOS (native) driver. The stock SQLiter stack is kept — SQLCipher is linked in
 * place of the system SQLite (vendored xcframework, see this module's build script), so SQLiter's
 * `DatabaseConfiguration.Encryption` applies the key as `PRAGMA key` on every connection open.
 *
 * The passphrase comes from the same [DatabaseKeyProvider] as on Android (KSafe vault, Keychain /
 * Secure-Enclave rooted). SQLiter takes the key as a SQL string, so the raw 32 CSPRNG bytes are
 * hex-armored here; SQLCipher then treats that string as a passphrase (PBKDF2). Android keys the DB
 * with the raw bytes instead — the two file formats intentionally diverge, the DB never crosses
 * devices (encryption is per-device).
 */
class NativeDatabaseDriverFactory(
    private val keyProvider: DatabaseKeyProvider,
) : DatabaseDriverFactory {

    override suspend fun create(): SqlDriver {
        val key = keyProvider.passphrase().hexEncodedString()
        return try {
            openProbed(key)
        } catch (e: MissingSqlCipherException) {
            // Build/link defect, not a data problem — never wipe the user's DB for it.
            throw e
        } catch (e: Exception) {
            // Wrong key / corrupt file: same recovery as Android — drop and recreate.
            deleteNativeDatabaseFile()
            openProbed(key)
        }
    }

    /**
     * Build the driver and force the SQLCipher open NOW so a wrong-key / corrupt-file failure
     * surfaces here — where we can recover — instead of lazily on the first DAO query. The
     * `cipher_version` probe also fails hard if the binary was accidentally linked against the
     * system SQLite (which ignores `PRAGMA key` and would leave the file in plaintext).
     */
    private fun openProbed(key: String): SqlDriver {
        val driver = NativeSqliteDriver(
            schema = BitMessageDatabase.Schema,
            name = DB_FILE_NAME,
            onConfiguration = { config ->
                config.copy(encryptionConfig = DatabaseConfiguration.Encryption(key = key))
            },
        )
        try {
            val cipherVersion = driver.executeQuery(
                identifier = null,
                sql = "PRAGMA cipher_version;",
                mapper = { cursor ->
                    QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
                },
                parameters = 0,
            ).value
            if (cipherVersion.isNullOrEmpty()) {
                throw MissingSqlCipherException(
                    "SQLCipher is not linked (PRAGMA cipher_version returned nothing) — the database would be plaintext",
                )
            }
            // A value-returning PRAGMA on real table data forces the key check against the file.
            driver.executeQuery(null, "PRAGMA user_version;", { QueryResult.Value(Unit) }, 0)
        } catch (e: Exception) {
            driver.close()
            throw e
        }
        return driver
    }

    private class MissingSqlCipherException(message: String) : IllegalStateException(message)
}
