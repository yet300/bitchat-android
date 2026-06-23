package com.app.database.di

import com.app.database.db.DatabaseDriverFactory
import com.app.database.db.NativeDatabaseDriverFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** iOS (native) platform binding for the database driver factory. */
@ContributesTo(AppScope::class)
@BindingContainer
object NativeDatabaseBindings {

    @SingleIn(AppScope::class)
    @Provides
    fun provideDatabaseDriverFactory(): DatabaseDriverFactory = NativeDatabaseDriverFactory()
}
