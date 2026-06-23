package com.app.database.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.app.database.BitMessageDatabase

/**
 * iOS (native) driver. SQLCipher on the SQLDelight native driver is a marked follow-up (FIX_BACKLOG
 * §F): until it is wired with a Secure-Enclave-rooted passphrase, this opens a plain NativeSqliteDriver
 * — the database is NOT yet encrypted at rest on iOS. iOS is not a shipping target yet (Android-first),
 * so this keeps the native target buildable without blocking on the SQLCipher-native work.
 */
class NativeDatabaseDriverFactory : DatabaseDriverFactory {

    override suspend fun create(): SqlDriver =
        NativeSqliteDriver(BitMessageDatabase.Schema, DB_FILE_NAME)
}
