@file:OptIn(ExperimentalTime::class)

package com.app.transport.mesh

import com.app.transport.MeshConstants
import com.app.transport.NoOpMeshTelemetry
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.peerIdToRoutingBytes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * P0.1: multi-hop frames must keep [RoutedPacket.peerID] as the claimed logical origin
 * (for signature/Noise), not the previous radio hop bound on the ingress link.
 */
class BleBearerLogicalOriginTest {

    private val localPeer = "0a0b0c0d0e0f1011"
    private val originPeer = "aaaaaaaaaaaaaaaa"
    private val hopPeer = "bbbbbbbbbbbbbbbb"
    private val linkAddress = "link-hop-1"

    private class FakeTransport : BearerTransport {
        override var delegate: BearerTransportDelegate? = null
        override val addressPeerMap: MutableMap<String, String> = mutableMapOf()
        override fun startServices(): Boolean = true
        override fun stopServices() {}
        override fun broadcastPacket(routed: RoutedPacket) {}
        override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean = true
        override fun cancelTransfer(transferId: String): Boolean = true
        override fun isClientConnection(address: String): Boolean? = true
        override fun setNicknameResolver(resolver: (String) -> String?) {}
        override fun setMeshServiceActive(active: Boolean) {}
        override fun setAppIsActive(active: Boolean) {}
        override fun startServer() {}
        override fun stopServer() {}
        override fun startClient() {}
        override fun stopClient() {}
        override fun getConnectedDeviceEntries(): List<Triple<String, Boolean, Int?>> = emptyList()
        override fun getLocalAdapterAddress(): String? = null
        override fun connectToAddress(address: String): Boolean = true
        override fun disconnectAddress(address: String) {}
        override fun getDebugInfo(): String = ""
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    private fun messageFromOrigin(): BitchatPacket = BitchatPacket(
        version = 1u,
        type = MessageType.MESSAGE.value,
        senderID = peerIdToRoutingBytes(originPeer),
        recipientID = null,
        timestamp = nowMs().toULong(),
        payload = "relayed".encodeToByteArray(),
        signature = null,
        ttl = (MeshConstants.MESSAGE_TTL_HOPS - 1u).toUByte(),
    )

    @Test
    fun multiHopMessage_preservesLogicalOriginInPeerID() = runBlocking {
        val transport = FakeTransport()
        // Link is bound to the previous hop, not the message author.
        transport.addressPeerMap[linkAddress] = hopPeer

        val bearer = BleBearer(
            myPeerID = localPeer,
            debugSettingsManager = NoOpMeshTelemetry,
            connectionManagerFactory = { transport },
            nowMs = { nowMs() },
        )
        val delegate = requireNotNull(transport.delegate)

        delegate.onPacketReceived(messageFromOrigin(), hopPeer, linkAddress)

        val routed = withTimeoutOrNull(2_000) { bearer.incoming.first() }
        assertNotNull(routed)
        assertEquals(
            originPeer,
            routed.peerID,
            "peerID must be claimed origin for crypto, not the previous hop",
        )
        assertEquals(linkAddress, routed.relayAddress)
        assertEquals(
            hopPeer,
            routed.previousHopPeerID,
            "previous hop must still be available as local metadata",
        )
    }

    @Test
    fun unboundLink_peerIDIsClaimedSender() = runBlocking {
        val transport = FakeTransport()
        val bearer = BleBearer(
            myPeerID = localPeer,
            debugSettingsManager = NoOpMeshTelemetry,
            connectionManagerFactory = { transport },
            nowMs = { nowMs() },
        )
        val delegate = requireNotNull(transport.delegate)

        delegate.onPacketReceived(messageFromOrigin(), originPeer, linkAddress)

        val routed = withTimeoutOrNull(2_000) { bearer.incoming.first() }
        assertNotNull(routed)
        assertEquals(originPeer, routed.peerID)
        // With no bind, receivedFrom falls back to claimed sender.
        assertEquals(originPeer, routed.previousHopPeerID)
    }

    @Test
    fun selfLoopback_isDropped() = runBlocking {
        val transport = FakeTransport()
        val bearer = BleBearer(
            myPeerID = localPeer,
            debugSettingsManager = NoOpMeshTelemetry,
            connectionManagerFactory = { transport },
            nowMs = { nowMs() },
        )
        val delegate = requireNotNull(transport.delegate)
        val selfPacket = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = peerIdToRoutingBytes(localPeer),
            recipientID = null,
            timestamp = nowMs().toULong(),
            payload = "me".encodeToByteArray(),
            signature = null,
            ttl = 7u,
        )
        delegate.onPacketReceived(selfPacket, localPeer, linkAddress)
        val routed = withTimeoutOrNull(300) { bearer.incoming.first() }
        assertNull(routed)
    }
}
