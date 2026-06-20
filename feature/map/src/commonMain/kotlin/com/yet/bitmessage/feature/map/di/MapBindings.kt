package com.yet.bitmessage.feature.map.di

import com.yet.bitmessage.feature.map.DefaultMapComponentFactory
import com.yet.bitmessage.feature.map.MapComponent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo

@ContributesTo(AppScope::class)
@BindingContainer
abstract class MapBindings {
    @Binds
    internal abstract val DefaultMapComponentFactory.bindMapComponentFactory: MapComponent.Factory
}
