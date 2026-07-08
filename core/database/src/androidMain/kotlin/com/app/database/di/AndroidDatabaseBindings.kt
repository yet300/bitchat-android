package com.app.database.di

import android.content.Context
import com.app.database.BitMessageDatabase
import com.app.database.db.DB_FILE_NAME
import com.app.database.db.DatabaseDriverFactory
import com.app.domain.repository.DatabaseKeyProvider
import com.yet.sqlcipher.AndroidSqlCipherDriverFactory
import com.yet.sqlcipher.SqlCipherConfig
import com.yet.sqlcipher.SqlCipherRecovery
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Android platform binding for the SQLCipher driver factory: adapts the app's
 * [DatabaseKeyProvider] (KSafe vault, Keystore/TEE-rooted) to the sqlcipher-driver library.
 * Recovery is drop-and-recreate — the DB is a per-device cache re-fillable from the mesh/Nostr.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object AndroidDatabaseBindings {

    @SingleIn(AppScope::class)
    @Provides
    fun provideDatabaseDriverFactory(
        context: Context,
        keyProvider: DatabaseKeyProvider,
    ): DatabaseDriverFactory {
        val factory = AndroidSqlCipherDriverFactory(context)
        val config = SqlCipherConfig(
            name = DB_FILE_NAME,
            key = { keyProvider.passphrase() },
            recovery = SqlCipherRecovery.Recreate,
        )
        return DatabaseDriverFactory { factory.create(BitMessageDatabase.Schema, config) }
    }
}
