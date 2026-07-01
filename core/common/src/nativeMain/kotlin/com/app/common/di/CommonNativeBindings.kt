package com.app.common.di

import dev.brewkits.grant.GrantFactory
import dev.brewkits.grant.GrantManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * nativeMain edge for :core:common. Builds the Grant [GrantManager] on Apple platforms, where no
 * context is required (Grant uses an in-memory store — there is no process-death restart-history
 * concern on iOS). The commonMain [com.app.common.permission.GrantPermissionController] drives it.
 *
 * grant-core isolates each permission's Apple framework, so only Camera/Notification handlers are
 * linked here — no phantom NSUsageDescription keys from unused frameworks. Optional modules
 * (Bluetooth, Location-always, …) would each need their own artifact + `initialize()` at app start.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object CommonNativeBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun provideGrantManager(): GrantManager = GrantFactory.create()
}
