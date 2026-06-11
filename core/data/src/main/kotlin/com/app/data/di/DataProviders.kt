package com.app.data.di

import com.app.data.routing.MessageRouter
import com.app.data.routing.RoutingCore
import com.app.transport.mesh.BluetoothMeshService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import com.app.transport.routing.PeerKeyResolver
import com.app.transport.routing.SessionInitiator
import kotlinx.coroutines.CoroutineScope

/**
 * Platform-agnostic @Provides bindings for the routing layer.
 *
 * Separated from [DataBindings] (abstract class, @Binds only) because @Provides requires
 * a concrete object. Must stay public: an internal @ContributesTo container is invisible
 * to the :app graph aggregation (verified — Metro/MissingBinding for MessageRouter). Placed in :core:data so it can reference internal types
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
    internal fun provideMessageRouter(
        routingCore: RoutingCore,
        scope: CoroutineScope,
    ): MessageRouter = MessageRouter(routingCore, scope)

    /** Narrow mesh ports (ISP) for the routing policy — implemented over BMS. */
    @Provides
    fun provideSessionInitiator(mesh: BluetoothMeshService): SessionInitiator =
        SessionInitiator { peerID -> mesh.initiateNoiseHandshake(peerID) }

    @Provides
    fun providePeerKeyResolver(mesh: BluetoothMeshService): PeerKeyResolver =
        PeerKeyResolver { peerID ->
            try {
                mesh.getPeerInfo(peerID)?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
            } catch (_: Exception) {
                null
            }
        }
}
