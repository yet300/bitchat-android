package com.app.database.db

import app.cash.sqldelight.db.SqlDriver

/**
 * Platform factory for the database [SqlDriver]. Both platforms open a SQLCipher-encrypted driver
 * via `io.github.yet300:sqlcipher-driver`, keyed from the hardware-rooted passphrase (see the DI
 * bindings in androidMain/nativeMain). An interface (rather than expect/actual) so tests can
 * substitute an in-memory driver and DI can pick the platform implementation.
 */
fun interface DatabaseDriverFactory {

    /** Open (creating the schema on first run) and return the driver. */
    suspend fun create(): SqlDriver
}

/** On-disk database file name (shared by every platform driver; also used by the panic wiper). */
const val DB_FILE_NAME = "bitmessage.db"
