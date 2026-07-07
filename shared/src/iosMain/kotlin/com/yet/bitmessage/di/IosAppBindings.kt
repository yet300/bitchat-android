package com.yet.bitmessage.di

import com.app.domain.model.TransportKind
import com.app.domain.model.TransportState
import com.app.domain.model.TransportStatus
import com.app.domain.repository.BatteryOptimizationRepository
import com.app.domain.repository.ConnectivityRepository
import com.app.transport.mesh.MeshLifecycleController
import com.app.transport.notification.ServiceNotifier
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * iOS app-layer leaves that Android provides from its notification/OS cluster. The mesh itself is
 * the shared `MeshCoordinator` (provided in commonMain `DataBindings`) over the CoreBluetooth
 * bearer contributed by `NativeDataBindings`; only the genuinely-platform leaves live here.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object IosAppBindings {

    /** iOS has no battery-optimization exemption concept — always exempt, never prompt. */
    @Provides
    fun provideBatteryOptimizationRepository(): BatteryOptimizationRepository =
        object : BatteryOptimizationRepository {
            override fun isExempt(): Boolean = true
            override fun hasAsked(): Boolean = true
            override fun markAsked() = Unit
            override suspend fun requestExemption() = Unit
        }

    /**
     * Transport statuses for the connectivity sheet. Bluetooth reflects the live mesh lifecycle;
     * Wi-Fi Aware does not exist on iOS. Recomputed on each collection (the sheet re-subscribes
     * when shown).
     */
    @Provides
    fun provideConnectivityRepository(
        meshLifecycle: MeshLifecycleController,
    ): ConnectivityRepository =
        object : ConnectivityRepository {
            override fun observe(): Flow<List<TransportStatus>> = flow {
                emit(
                    listOf(
                        TransportStatus(TransportKind.INTERNET, TransportState.ON),
                        TransportStatus(TransportKind.TOR, TransportState.OFF),
                        TransportStatus(
                            TransportKind.BLUETOOTH,
                            if (meshLifecycle.isMeshActive) TransportState.ON else TransportState.OFF,
                        ),
                        TransportStatus(TransportKind.WIFI_AWARE, TransportState.UNAVAILABLE),
                    ),
                )
            }

            override suspend fun enable(kind: TransportKind) {
                if (kind == TransportKind.BLUETOOTH) meshLifecycle.start()
            }
        }

    /**
     * Background DM notifications are an iOS follow-up (UNUserNotificationCenter); the mesh engine
     * only calls this when no UI delegate is attached, which cannot happen while the Compose root
     * is alive.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideServiceNotifier(): ServiceNotifier =
        object : ServiceNotifier {
            override fun setAppBackgroundState(inBackground: Boolean) = Unit
            override fun showPrivateMessageNotification(
                senderPeerID: String,
                senderNickname: String,
                messageContent: String,
            ) = Unit
        }
}
