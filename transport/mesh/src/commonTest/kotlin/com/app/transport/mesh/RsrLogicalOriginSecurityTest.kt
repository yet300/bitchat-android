package com.app.transport.mesh

import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.peerIdToRoutingBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * P0.2: solicitation peer for RSR is the hop (not the logical author).
 * Pure guard tests without EncryptionService — SecurityManager wiring is covered
 * in androidHostTest [RsrMultiHopSecurityManagerTest].
 */
class RsrLogicalOriginSecurityTest {

    private val hopPeer = "bbbbbbbbbbbbbbbb"
    private val authorPeer = "aaaaaaaaaaaaaaaa"
    private val now = 1_700_000_000_000L

    private fun oldRsrLeave() = BitchatPacket(
        version = 1u,
        type = MessageType.LEAVE.value,
        senderID = peerIdToRoutingBytes(authorPeer),
        recipientID = null,
        timestamp = 1_000_000uL,
        payload = byteArrayOf(),
        signature = null,
        ttl = 0u,
        isRSR = true,
    )

    @Test
    fun validatePayload_rsrUsesHopForSolicitation() {
        val packet = oldRsrLeave()
        // Gate open only for hop B
        assertNull(
            BleIngressPacketGuard.validatePayload(
                packet = packet,
                peerID = hopPeer,
                nowMs = now,
                isRSR = true,
                isValidSyncResponse = { it == hopPeer },
            ),
        )
        // Same packet keyed as author C → unsolicited
        val rejected = BleIngressPacketGuard.validatePayload(
            packet = packet,
            peerID = authorPeer,
            nowMs = now,
            isRSR = true,
            isValidSyncResponse = { it == hopPeer },
        )
        assertIs<BleIngressPacketGuard.Rejection.InvalidRSR>(rejected)
        assertEquals(authorPeer, rejected.peerID)
    }

    @Test
    fun packetContext_rsrValidationPeerIsHop_claimedSenderIsAuthor() {
        val packet = oldRsrLeave()
        val result = BleIngressLinkRegistry.packetContext(
            packet = packet,
            claimedSenderID = authorPeer,
            boundPeerID = hopPeer,
            localPeerID = "0a0b0c0d0e0f1011",
            directAnnounceTTL = 7u,
            isRSR = true,
        )
        val success = assertIs<BleIngressLinkRegistry.Companion.Result.Success>(result)
        assertEquals(hopPeer, success.context.receivedFromPeerID)
        assertEquals(hopPeer, success.context.validationPeerID, "ingress solicitation key")
        // BleBearer must map claimedSenderID → RoutedPacket.peerID, not validationPeerID
    }

    @Test
    fun selfAuthoredSyncResponse_onlyIsRsrTtlZero() {
        val local = "0a0b0c0d0e0f1011"
        val base = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = peerIdToRoutingBytes(local),
            recipientID = null,
            timestamp = 1u,
            payload = byteArrayOf(1),
            signature = null,
            ttl = 0u,
            isRSR = true,
        )
        assertEquals(
            true,
            BleIngressLinkRegistry.isSelfAuthoredSyncResponse(base, local, local),
        )
        assertEquals(
            false,
            BleIngressLinkRegistry.isSelfAuthoredSyncResponse(
                base.copy(isRSR = false), local, local,
            ),
        )
        assertEquals(
            false,
            BleIngressLinkRegistry.isSelfAuthoredSyncResponse(
                base.copy(ttl = 3u), local, local,
            ),
        )
        assertEquals(
            false,
            BleIngressLinkRegistry.isSelfAuthoredSyncResponse(base, authorPeer, local),
        )
    }

    @Test
    fun packetContext_allowsSelfAuthoredRsr() {
        val local = "0a0b0c0d0e0f1011"
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = peerIdToRoutingBytes(local),
            recipientID = null,
            timestamp = 1u,
            payload = byteArrayOf(1),
            signature = null,
            ttl = 0u,
            isRSR = true,
        )
        val result = BleIngressLinkRegistry.packetContext(
            packet = packet,
            claimedSenderID = local,
            boundPeerID = hopPeer,
            localPeerID = local,
            directAnnounceTTL = 7u,
            isRSR = true,
        )
        assertIs<BleIngressLinkRegistry.Companion.Result.Success>(result)
    }

    @Test
    fun packetContext_rejectsOrdinarySelfLoopback() {
        val local = "0a0b0c0d0e0f1011"
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = peerIdToRoutingBytes(local),
            recipientID = null,
            timestamp = 1u,
            payload = byteArrayOf(1),
            signature = null,
            ttl = 3u,
            isRSR = false,
        )
        val result = BleIngressLinkRegistry.packetContext(
            packet = packet,
            claimedSenderID = local,
            boundPeerID = hopPeer,
            localPeerID = local,
            directAnnounceTTL = 7u,
            isRSR = false,
        )
        assertIs<BleIngressLinkRegistry.Companion.Result.Failure>(result)
    }
}
