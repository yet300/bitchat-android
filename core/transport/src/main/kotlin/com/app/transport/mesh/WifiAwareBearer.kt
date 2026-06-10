package com.app.transport.mesh

import android.content.Context
import com.app.transport.model.RoutedPacket
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Wi-Fi Aware (NAN) bearer — stub implementation.
 *
 * This class exists to prove the OCP property of [MeshBearer]: adding a new
 * transport medium requires only implementing [MeshBearer] and registering the
 * class via Metro `@Binds @IntoSet` — no changes to [BleBearer], [MeshNetwork],
 * or [BluetoothMeshService] are needed.
 *
 * TODO: replace the stub methods with a real Wi-Fi Aware / NAN implementation.
 */
/**
 * OCP proof: registering this bearer requires only [ContributesIntoSet] here —
 * no changes to [MeshNetwork], [BleBearer], or [BluetoothMeshService].
 * The bound type ([MeshBearer]) is inferred from the single declared supertype.
 */
@ContributesIntoSet(AppScope::class)
@Inject
class WifiAwareBearer(
    @Suppress("UNUSED_PARAMETER") context: Context,
) : MeshBearer {

    override val id: BearerId = BearerId.WIFI_AWARE

    override val incoming: Flow<RoutedPacket> = emptyFlow()

    override val neighbors: StateFlow<Set<PeerLink>> = MutableStateFlow(emptySet())

    override val events: Flow<BearerEvent> = emptyFlow()

    override fun bindPeer(peerID: String, linkAddress: String) = Unit  // No links tracked yet

    override fun start(): Boolean = false   // Not yet implemented

    override fun stop() = Unit

    override fun broadcast(packet: RoutedPacket) = Unit

    override fun sendToPeer(peerID: String, packet: RoutedPacket): Boolean = false

    override fun cancelTransfer(transferId: String): Boolean = false
}
