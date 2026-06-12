package com.yet.bitmessage.feature.root.di

import com.yet.bitmessage.feature.root.DefaultRootComponentFactory
import com.yet.bitmessage.feature.root.RootComponent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo

@ContributesTo(AppScope::class)
@BindingContainer
abstract class RootBindings {
    @Binds
    internal abstract val DefaultRootComponentFactory.bindRootComponentFactory: RootComponent.Factory
}
