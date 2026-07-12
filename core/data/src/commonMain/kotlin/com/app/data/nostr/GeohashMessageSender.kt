package com.app.data.nostr

import com.app.common.AppDispatchers
import com.app.common.utils.Log
import com.app.transport.nostr.NostrIdentityBridge
import com.app.transport.nostr.NostrProtocol
import com.app.transport.nostr.NostrRelayManager
import com.app.transport.nostr.PoWPreferenceManager
import com.app.transport.nostr.RelayDirectory
import com.app.transport.mesh.MeshService
import com.app.transport.mesh.NostrGatewaySender
import com.app.transport.model.PeerCapabilities
import com.app.data.gateway.GatewayRuntime
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Posts public geohash chat messages (Nostr kind-20000 ephemeral events) over the existing
 * transport relay stack. Counterpart to [NostrMessageSender] (which handles DMs); kept separate
 * so the routing transport depends only on this narrow public-post capability.
 *
 * The post is signed with the per-geohash derived identity (unlinkable across geohashes, iOS-
 * compatible) and routed to the geohash's relays. PoW mining is applied when enabled.
 */
@SingleIn(AppScope::class)
@Inject
class GeohashMessageSender(
    private val relayManager: NostrRelayManager,
    private val relayDirectory: RelayDirectory,
    private val nostrIdentityBridge: NostrIdentityBridge,
    private val powPreferenceManager: PoWPreferenceManager,
    private val meshService: MeshService,
    @Suppress("UNUSED_PARAMETER") gatewayRuntime: GatewayRuntime,
    dispatchers: AppDispatchers,
) {

    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())
    private val gatewaySender = NostrGatewaySender(
        relaysConnected = { relayManager.isConnected.value },
        gatewayPeers = {
            meshService.connectedPeerIDs().filter { peerId ->
                meshService.getPeerInfo(peerId)?.capabilities?.contains(PeerCapabilities.GATEWAY) == true
            }
        },
        sendDirected = meshService::sendNostrCarrier,
    )

    fun sendPublic(content: String, geohash: String, nickname: String?) {
        scope.launch {
            try {
                val identity = nostrIdentityBridge.deriveIdentity(geohash)
                val event = NostrProtocol.createEphemeralGeohashEvent(
                    content = content,
                    geohash = geohash,
                    senderIdentity = identity,
                    powPreferenceManager = powPreferenceManager,
                    nickname = nickname,
                )
                relayManager.sendEventToGeohash(event, geohash, relayDirectory)
                gatewaySender.uplink(event.id, event.toJsonString().encodeToByteArray(), geohash)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send geohash message to #$geohash: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "GeohashMessageSender"
    }
}
