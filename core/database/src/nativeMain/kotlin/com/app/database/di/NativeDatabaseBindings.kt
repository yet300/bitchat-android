package com.app.database.di

import com.app.database.BitMessageDatabase
import com.app.database.db.DB_FILE_NAME
import com.app.database.db.DatabaseDriverFactory
import com.app.domain.repository.DatabaseKeyProvider
import com.yet.sqlcipher.NativeSqlCipherDriverFactory
import com.yet.sqlcipher.SqlCipherConfig
import com.yet.sqlcipher.SqlCipherRecovery
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * iOS (native) platform binding for the SQLCipher driver factory: adapts the app's
 * [DatabaseKeyProvider] (Keychain/Secure-Enclave-rooted) to the sqlcipher-driver library, whose
 * klib bundles the static SQLCipher the binary links against. Recovery is drop-and-recreate —
 * the DB is a per-device cache re-fillable from the mesh/Nostr.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object NativeDatabaseBindings {

    @SingleIn(AppScope::class)
    @Provides
    fun provideDatabaseDriverFactory(keyProvider: DatabaseKeyProvider): DatabaseDriverFactory {
        val factory = NativeSqlCipherDriverFactory()
        val config = SqlCipherConfig(
            name = DB_FILE_NAME,
            key = { keyProvider.passphrase() },
            recovery = SqlCipherRecovery.Recreate,
        )
        return DatabaseDriverFactory { factory.create(BitMessageDatabase.Schema, config) }
    }
}
