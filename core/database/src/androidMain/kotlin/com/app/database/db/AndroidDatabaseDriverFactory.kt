package com.app.database.db

import android.content.Context
import android.util.Log
import android.database.sqlite.SQLiteException
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.app.database.BitMessageDatabase
import com.app.domain.repository.DatabaseKeyProvider
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * SQLCipher-encrypted Android driver. The passphrase is unwrapped from the hardware-rooted secret
 * store by [DatabaseKeyProvider] (provided in :shared androidMain) — this factory only wires the
 * SQLCipher SupportSQLiteOpenHelper, carrying no key-management dependency itself.
 */
class AndroidDatabaseDriverFactory(
    private val context: Context,
    private val keyProvider: DatabaseKeyProvider,
) : DatabaseDriverFactory {

    override suspend fun create(): SqlDriver {
        System.loadLibrary("sqlcipher")
        return try {
            openProbed()
        } catch (e: SQLiteException) {
            Log.w(TAG, "Database unreadable (${e.message}); recreating from scratch", e)
            context.deleteDatabase(DB_FILE_NAME)
            openProbed()
        }
    }

    /**
     * Build the driver and force the SQLCipher open NOW (a trivial PRAGMA) so a wrong-key / corrupt
     * failure surfaces here — where we can recover — instead of lazily on the first DAO query, where
     * it would crash a background coroutine.
     */
    private suspend fun openProbed(): SqlDriver {
        // SupportOpenHelperFactory takes ownership of the passphrase bytes and zeroes them after open;
        // fetch a fresh copy on each attempt.
        val factory = SupportOpenHelperFactory(keyProvider.passphrase())
        val driver = AndroidSqliteDriver(
            schema = BitMessageDatabase.Schema,
            context = context,
            name = DB_FILE_NAME,
            factory = factory,
        )
        // executeQuery (not execute) — a value-returning PRAGMA must go through the rawQuery path;
        // running it forces the SQLCipher open + key check now, throwing on a wrong key / corrupt file.
        driver.executeQuery(null, "PRAGMA user_version;", { QueryResult.Value(Unit) }, 0)
        return driver
    }

    private companion object {
        const val TAG = "AndroidDbDriver"
    }
}
