package com.yet.bitmessage.di

import com.app.domain.model.TransportKind
import com.app.domain.model.TransportState
import com.app.domain.model.TransportStatus
import com.app.domain.repository.BatteryOptimizationRepository
import com.app.domain.repository.ConnectivityRepository
import com.app.domain.repository.MeshResetPort
import com.app.transport.mesh.MeshBearer
import com.app.transport.mesh.MeshService
import com.yet.bitmessage.mesh.NoOpMeshService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * iOS app-layer leaves that Android provides from its mesh/OS cluster. The CoreBluetooth mesh is
 * the deferred transport-native follow-up, so the mesh-facing ports are honest no-ops here:
 * Nostr-over-internet is the only live transport on iOS for now (a valid runtime — mesh is
 * additive), and the connectivity sheet reports exactly that.
 */
@ContributesTo(AppScope::class)
@BindingContainer
abstract class IosAppBindings {

    /** No mesh bearers on iOS yet — MeshNetwork tolerates an empty set. */
    @Multibinds(allowEmpty = true)
    abstract fun meshBearers(): Set<MeshBearer>

    companion object {

        /** iOS has no battery-optimization exemption concept — always exempt, never prompt. */
        @Provides
        fun provideBatteryOptimizationRepository(): BatteryOptimizationRepository =
            object : BatteryOptimizationRepository {
                override fun isExempt(): Boolean = true
                override fun hasAsked(): Boolean = true
                override fun markAsked() = Unit
                override suspend fun requestExemption() = Unit
            }

        @Provides
        fun provideConnectivityRepository(): ConnectivityRepository =
            object : ConnectivityRepository {
                override fun observe(): Flow<List<TransportStatus>> = flowOf(
                    listOf(
                        TransportStatus(TransportKind.INTERNET, TransportState.ON),
                        TransportStatus(TransportKind.TOR, TransportState.OFF),
                        TransportStatus(TransportKind.BLUETOOTH, TransportState.UNAVAILABLE),
                        TransportStatus(TransportKind.WIFI_AWARE, TransportState.UNAVAILABLE),
                    ),
                )

                override suspend fun enable(kind: TransportKind) = Unit
            }

        /** Nothing to reset until a native mesh exists; the panic wipe's other steps still run. */
        @Provides
        fun provideMeshResetPort(): MeshResetPort =
            object : MeshResetPort {
                override suspend fun reset() = Unit
            }

        @Provides
        @SingleIn(AppScope::class)
        fun provideMeshService(): MeshService = NoOpMeshService()
    }
}
