package com.app.database.db

import app.cash.sqldelight.db.SqlDriver

/**
 * Platform factory for the database [SqlDriver]. Android opens a SQLCipher-encrypted driver with the
 * hardware-rooted passphrase; iOS (native) opens a SQLDelight native driver — SQLCipher on the native
 * driver is a marked follow-up (FIX_BACKLOG §F). An interface (rather than expect/actual) so tests can
 * substitute an in-memory driver and DI can pick the platform implementation.
 */
interface DatabaseDriverFactory {

    /** Open (creating the schema on first run) and return the driver. */
    suspend fun create(): SqlDriver
}

/** On-disk database file name (shared by every platform driver). */
internal const val DB_FILE_NAME = "bitmessage.db"
