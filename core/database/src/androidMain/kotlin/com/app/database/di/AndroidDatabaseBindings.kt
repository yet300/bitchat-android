package com.app.database.di

import android.content.Context
import com.app.database.db.AndroidDatabaseDriverFactory
import com.app.database.db.DatabaseDriverFactory
import com.app.domain.repository.DatabaseKeyProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** Android platform binding for the SQLCipher driver factory. */
@ContributesTo(AppScope::class)
@BindingContainer
object AndroidDatabaseBindings {

    @SingleIn(AppScope::class)
    @Provides
    fun provideDatabaseDriverFactory(
        context: Context,
        keyProvider: DatabaseKeyProvider,
    ): DatabaseDriverFactory = AndroidDatabaseDriverFactory(context, keyProvider)
}
