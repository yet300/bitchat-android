package com.app.database.db

import app.cash.sqldelight.db.SqlDriver
import com.app.common.AppDispatchers
import com.app.database.BitMessageDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * App-scoped owner of the single [BitMessageDatabase] handle and its driver lifecycle. The database is
 * opened lazily on first access (so the hardware-key unwrap and SQLCipher open happen off the critical
 * startup path) and reused thereafter. The DAOs go through [getDb]; they never see the driver.
 *
 * [close] releases the driver and drops the cached handle, so the next [getDb] reopens — used after a
 * panic crypto-erase.
 */
class DatabaseManager(
    private val driverFactory: DatabaseDriverFactory,
    private val dispatchers: AppDispatchers,
) {

    private val mutex = Mutex()
    private var driver: SqlDriver? = null
    private var database: BitMessageDatabase? = null

    suspend fun getDb(): BitMessageDatabase {
        database?.let { return it }
        return mutex.withLock {
            database ?: open()
        }
    }

    private suspend fun open(): BitMessageDatabase = withContext(dispatchers.io) {
        val newDriver = driverFactory.create()
        val db = BitMessageDatabase(newDriver)
        driver = newDriver
        database = db
        db
    }

    fun close() {
        driver?.close()
        driver = null
        database = null
    }
}
