@file:OptIn(ExperimentalTime::class)

package com.app.transport.mesh

import com.app.transport.NoOpMeshTelemetry
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
 * P0.2: BLE ingress must accept solicited RSR and drop unsolicited RSR.
 * Multi-hop RSR must keep [RoutedPacket.peerID] as the logical author while
 * solicitation is checked against the previous hop.
 */
class BleBearerRsrIngressTest {

    private val localPeer = "0a0b0c0d0e0f1011"
    private val hopPeer = "bbbbbbbbbbbbbbbb"
    private val originPeer = "aaaaaaaaaaaaaaaa"
    private val linkAddress = "link-rsr-1"

    private class FakeTransport : BearerTransport {
        override var delegate: BearerTransportDelegate? = null
        override val addressPeerMap: MutableMap<String, String> = mutableMapOf()
        override fun startServices(): Boolean = true
        override fun stopServices() {}
        override fun broadcastPacket(routed: com.app.transport.model.RoutedPacket) {}
        override fun sendToPeer(peerID: String, routed: com.app.transport.model.RoutedPacket): Boolean = true
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

    /** Old timestamp would fail 120s skew if RSR gate were bypassed incorrectly. */
    private fun oldRsrPacket(): BitchatPacket = BitchatPacket(
        version = 1u,
        type = MessageType.MESSAGE.value,
        senderID = peerIdToRoutingBytes(originPeer),
        recipientID = null,
        timestamp = (nowMs() - 600_000L).toULong(),
        payload = "sync-replay".encodeToByteArray(),
        signature = null,
        ttl = 0u,
        isRSR = true,
    )

    @Test
    fun unsolicitedRsr_isDropped() = runBlocking {
        val transport = FakeTransport()
        transport.addressPeerMap[linkAddress] = hopPeer
        val bearer = BleBearer(
            myPeerID = localPeer,
            debugSettingsManager = NoOpMeshTelemetry,
            connectionManagerFactory = { transport },
            nowMs = { nowMs() },
        )
        // Default isValidSyncResponse = { false }
        val delegate = requireNotNull(transport.delegate)
        delegate.onPacketReceived(oldRsrPacket(), hopPeer, linkAddress)
        val routed = withTimeoutOrNull(300) { bearer.incoming.first() }
        assertNull(routed, "unsolicited RSR must not enter the mesh engine")
        Unit
    }

    @Test
    fun solicitedRsr_isAcceptedDespiteOldTimestamp() = runBlocking {
        val transport = FakeTransport()
        transport.addressPeerMap[linkAddress] = hopPeer
        val bearer = BleBearer(
            myPeerID = localPeer,
            debugSettingsManager = NoOpMeshTelemetry,
            connectionManagerFactory = { transport },
            nowMs = { nowMs() },
        )
        bearer.isValidSyncResponse = { peerID -> peerID == hopPeer }
        val delegate = requireNotNull(transport.delegate)
        delegate.onPacketReceived(oldRsrPacket(), hopPeer, linkAddress)
        val routed = withTimeoutOrNull(2_000) { bearer.incoming.first() }
        assertNotNull(routed, "solicited RSR must pass ingress even with old timestamp")
        // hop (B) != author (C): peerID must remain logical author for signature/crypto.
        assertEquals(originPeer, routed.peerID, "RSR peerID must be packet.senderID (author C)")
        assertEquals(hopPeer, routed.previousHopPeerID, "RSR previous hop must be solicitation peer B")
        Unit
    }

    @Test
    fun solicitedRsr_wrongPeerStillDropped() = runBlocking {
        val transport = FakeTransport()
        transport.addressPeerMap[linkAddress] = hopPeer
        val bearer = BleBearer(
            myPeerID = localPeer,
            debugSettingsManager = NoOpMeshTelemetry,
            connectionManagerFactory = { transport },
            nowMs = { nowMs() },
        )
        // Window open for a different peer only.
        bearer.isValidSyncResponse = { peerID -> peerID == "cccccccccccccccc" }
        val delegate = requireNotNull(transport.delegate)
        delegate.onPacketReceived(oldRsrPacket(), hopPeer, linkAddress)
        val routed = withTimeoutOrNull(300) { bearer.incoming.first() }
        assertNull(routed)
        Unit
    }

    @Test
    fun solicitedRsr_solicitationAgainstAuthorNotHop_isDropped() = runBlocking {
        // Window open only for author C — real solicitation is registered on hop B.
        val transport = FakeTransport()
        transport.addressPeerMap[linkAddress] = hopPeer
        val bearer = BleBearer(
            myPeerID = localPeer,
            debugSettingsManager = NoOpMeshTelemetry,
            connectionManagerFactory = { transport },
            nowMs = { nowMs() },
        )
        bearer.isValidSyncResponse = { peerID -> peerID == originPeer }
        val delegate = requireNotNull(transport.delegate)
        delegate.onPacketReceived(oldRsrPacket(), hopPeer, linkAddress)
        val routed = withTimeoutOrNull(300) { bearer.incoming.first() }
        assertNull(routed, "RSR gate must key on hop B, not author C")
        Unit
    }
}
