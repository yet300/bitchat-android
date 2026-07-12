package com.app.data.gateway

import com.app.transport.MeshTelemetry
import com.app.transport.mesh.MeshService
import com.app.transport.nostr.NostrEvent
import com.app.transport.nostr.NostrRelayManager
import com.app.transport.nostr.RelayDirectory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/** Explicit mesh/relay adapter around [GatewayCoordinator]; registration only, no hidden coroutine. */
@OptIn(ExperimentalTime::class)
@SingleIn(AppScope::class)
@Inject
class GatewayRuntime(
    private val meshService: MeshService,
    relayManager: NostrRelayManager,
    relayDirectory: RelayDirectory,
    telemetry: MeshTelemetry,
    private val scope: CoroutineScope,
) {
    private var currentGeohash: () -> String? = { null }
    private var injectInbound: (NostrEvent) -> Unit = {}

    private val coordinator = GatewayCoordinator(
        enabled = meshService::isGatewayEnabled,
        relaysConnected = { relayManager.isConnected.value },
        nowSeconds = { Clock.System.now().epochSeconds },
        publish = { event, geohash -> relayManager.sendEventToGeohash(event, geohash, relayDirectory) },
        broadcast = meshService::broadcastNostrCarrier,
        currentGeohash = { currentGeohash() },
        injectInbound = { injectInbound(it) },
        telemetry = { telemetry.onGatewayEvent(it.name, it.reason) },
        scheduleDownlinkDrain = { delaySeconds, drain ->
            scope.launch {
                delay(delaySeconds.seconds)
                drain()
            }
        },
    )

    init {
        meshService.setNostrCarrierHandler(coordinator::handleMeshCarrier)
    }

    fun bindInbound(current: () -> String?, inject: (NostrEvent) -> Unit) {
        currentGeohash = current
        injectInbound = inject
    }

    fun onRelayEvent(event: NostrEvent, geohash: String) {
        coordinator.flushQueuedUplinks()
        coordinator.rebroadcastRelayEvent(event, geohash)
    }

    /** Called for the relay connectivity transition so a quiet recovered relay still drains. */
    fun onRelayConnectivityChanged(connected: Boolean) {
        if (connected) coordinator.flushQueuedUplinks()
    }

    fun onGatewayDisabled() = coordinator.clearQueues()
}
