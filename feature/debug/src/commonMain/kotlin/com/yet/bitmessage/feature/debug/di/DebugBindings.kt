package com.yet.bitmessage.feature.debug.di

import com.yet.bitmessage.feature.debug.DebugComponent
import com.yet.bitmessage.feature.debug.DefaultDebugComponentFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo

@ContributesTo(AppScope::class)
@BindingContainer
abstract class DebugBindings {
    @Binds
    internal abstract val DefaultDebugComponentFactory.bindDebugComponentFactory: DebugComponent.Factory
}
