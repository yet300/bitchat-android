package com.app.transport.mesh

import com.app.transport.MeshConstants
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.peerIdToRoutingBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BleIngressPacketGuardTest {

    private val local = "0a0b0c0d0e0f1011"
    private val peerA = "1111111111111111"
    private val peerB = "2222222222222222"
    private val now = 1_700_000_000_000L

    private fun packet(
        type: MessageType = MessageType.MESSAGE,
        sender: String = peerA,
        ttl: UByte = 7u,
        timestampMs: Long = now,
    ) = BitchatPacket(
        version = 1u,
        type = type.value,
        senderID = peerIdToRoutingBytes(sender),
        recipientID = null,
        timestamp = timestampMs.toULong(),
        payload = byteArrayOf(1, 2, 3),
        signature = null,
        ttl = ttl,
    )

    @Test
    fun selfLoopbackDropped() {
        val result = BleIngressPacketGuard.evaluate(
            packet = packet(sender = local),
            claimedSenderID = local,
            boundPeerID = null,
            localPeerID = local,
            directAnnounceTTL = MeshConstants.MESSAGE_TTL_HOPS,
            nowMs = now,
        )
        assertIs<BleIngressPacketGuard.EvaluateResult.Reject>(result)
        assertIs<BleIngressPacketGuard.Rejection.SelfLoopback>(result.rejection)
    }

    @Test
    fun requestSyncOnBoundLink_requiresMatchingSender() {
        val result = BleIngressPacketGuard.evaluate(
            packet = packet(type = MessageType.REQUEST_SYNC, sender = peerB),
            claimedSenderID = peerB,
            boundPeerID = peerA,
            localPeerID = local,
            directAnnounceTTL = MeshConstants.MESSAGE_TTL_HOPS,
            nowMs = now,
        )
        assertIs<BleIngressPacketGuard.EvaluateResult.Reject>(result)
        assertIs<BleIngressPacketGuard.Rejection.DirectSenderMismatch>(result.rejection)
    }

    @Test
    fun directAnnounceOnBoundLink_attributesToClaimedSender() {
        val result = BleIngressPacketGuard.evaluate(
            packet = packet(
                type = MessageType.ANNOUNCE,
                sender = peerB,
                ttl = MeshConstants.MESSAGE_TTL_HOPS,
            ),
            claimedSenderID = peerB,
            boundPeerID = peerA,
            localPeerID = local,
            directAnnounceTTL = MeshConstants.MESSAGE_TTL_HOPS,
            nowMs = now,
        )
        val accept = assertIs<BleIngressPacketGuard.EvaluateResult.Accept>(result)
        assertEquals(peerB, accept.context.receivedFromPeerID)
        assertEquals(peerB, accept.context.validationPeerID)
    }

    @Test
    fun boundLinkUsesBoundAsReceivedFrom() {
        val result = BleIngressPacketGuard.evaluate(
            packet = packet(sender = peerA),
            claimedSenderID = peerA,
            boundPeerID = peerA,
            localPeerID = local,
            directAnnounceTTL = MeshConstants.MESSAGE_TTL_HOPS,
            nowMs = now,
        )
        val accept = assertIs<BleIngressPacketGuard.EvaluateResult.Accept>(result)
        assertEquals(peerA, accept.context.receivedFromPeerID)
    }

    @Test
    fun timestampSkewRejected() {
        val result = BleIngressPacketGuard.evaluate(
            packet = packet(timestampMs = now - 200_000L),
            claimedSenderID = peerA,
            boundPeerID = null,
            localPeerID = local,
            directAnnounceTTL = MeshConstants.MESSAGE_TTL_HOPS,
            nowMs = now,
            maxTimestampSkewMs = 120_000L,
        )
        assertIs<BleIngressPacketGuard.EvaluateResult.Reject>(result)
        assertIs<BleIngressPacketGuard.Rejection.TimestampSkew>(result.rejection)
    }

    @Test
    fun validatePayloadAcceptsFreshTimestamp() {
        assertNull(
            BleIngressPacketGuard.validatePayload(
                packet = packet(timestampMs = now - 1_000L),
                peerID = peerA,
                nowMs = now,
            ),
        )
    }
}
