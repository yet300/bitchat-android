package com.app.database.db

import android.content.Context
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
        // SupportOpenHelperFactory takes ownership of the passphrase bytes and zeroes them after open.
        val factory = SupportOpenHelperFactory(keyProvider.passphrase())
        return AndroidSqliteDriver(
            schema = BitMessageDatabase.Schema,
            context = context,
            name = DB_FILE_NAME,
            factory = factory,
        )
    }
}
