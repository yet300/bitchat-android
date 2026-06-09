package com.app.data.di

import com.app.data.routing.MessageRouter
import com.app.data.routing.RoutingCore
import com.app.transport.mesh.BluetoothMeshService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Platform-agnostic @Provides bindings for the routing layer.
 *
 * Separated from [DataBindings] (abstract class, @Binds only) because @Provides requires
 * a concrete object. Placed in :core:data so it can reference internal types
 * (RouteSelector, MeshRouteStrategy, NostrRouteStrategy) that must not leak to :app.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object DataProviders {

    /**
     * Wraps the graph-owned [RouteSelector] in the legacy [MessageRouter] facade.
     * [MessageRouter] keeps the existing call-site API (god-classes, MeshServiceHolder)
     * unchanged until Phase C dissolves them.
     * Temporary Phase-D/DI-core bridge — retires with Phase C.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideMessageRouter(
        routingCore: RoutingCore,
        mesh: BluetoothMeshService,
    ): MessageRouter = MessageRouter.getInstance(routingCore, mesh)
}
