package com.app.common.di

import android.content.Context
import dev.brewkits.grant.GrantFactory
import dev.brewkits.grant.GrantManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * androidMain edge for :core:common. Builds the Grant [GrantManager] from the app [Context]; the
 * commonMain [com.app.common.permission.GrantPermissionController] drives it. Grant runs the system
 * dialog from its own transparent Activity, so no ActivityResult plumbing is needed here.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object CommonAndroidBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun provideGrantManager(context: Context): GrantManager =
        GrantFactory.create(context.applicationContext)
}
