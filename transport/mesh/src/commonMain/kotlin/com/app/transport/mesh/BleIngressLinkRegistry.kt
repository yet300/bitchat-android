package com.app.transport.mesh

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import com.app.common.encoding.hexEncodedString
import com.app.transport.crypto.Sha256
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType

/**
 * Ingress link memory and packet-context attribution — port of iOS
 * `Services/BLE/BLEIngressLinkRegistry.swift`.
 *
 * [messageId] is the local-only dedup key shared with [SecurityManager]
 * (sender-timestamp-type-sha256(payload).prefix(4)).
 */
sealed class BleIngressLinkId {
    data class Peripheral(val address: String) : BleIngressLinkId()
    data class Central(val address: String) : BleIngressLinkId()
}

data class BleIngressPacketContext(
    val receivedFromPeerID: String,
    val validationPeerID: String,
)

data class BleIngressLinkRecord(
    val link: BleIngressLinkId,
    val peerID: String,
    val timestampMs: Long,
)

sealed class BleIngressRejection {
    data class SelfLoopback(val packetType: UByte) : BleIngressRejection()
    data class DirectSenderMismatch(
        val boundPeerID: String,
        val claimedSenderID: String,
    ) : BleIngressRejection()
}

class BleIngressLinkRegistry {
    private val lock = Lock()
    private val ingressByMessageID = linkedMapOf<String, BleIngressLinkRecord>()

    val isEmpty: Boolean
        get() = lock.withLock { ingressByMessageID.isEmpty() }

    fun clear() = lock.withLock { ingressByMessageID.clear() }

    fun record(forPacket: BitchatPacket): BleIngressLinkRecord? = lock.withLock {
        ingressByMessageID[messageId(forPacket)]
    }

    /**
     * @return true if this is the first sighting within [lifetimeMs], false if a recent
     * duplicate on any link (caller should drop).
     */
    fun recordIfNew(
        packet: BitchatPacket,
        link: BleIngressLinkId,
        peerID: String,
        nowMs: Long,
        lifetimeMs: Long,
    ): Boolean = lock.withLock {
        val id = messageId(packet)
        val existing = ingressByMessageID[id]
        if (existing != null && nowMs - existing.timestampMs <= lifetimeMs) {
            return false
        }
        ingressByMessageID[id] = BleIngressLinkRecord(link, peerID, nowMs)
        // Bound growth: drop entries older than lifetime when map is large.
        if (ingressByMessageID.size > MAX_RECORDS) {
            val cutoff = nowMs - lifetimeMs
            val it = ingressByMessageID.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value.timestampMs < cutoff) it.remove()
            }
        }
        true
    }

    fun prune(beforeMs: Long) = lock.withLock {
        val it = ingressByMessageID.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.timestampMs < beforeMs) it.remove()
        }
    }

    companion object {
        private const val MAX_RECORDS = 4096

        fun messageId(packet: BitchatPacket): String {
            val digestPrefix = Sha256.digest(packet.payload).copyOf(4).hexEncodedString()
            return "${packet.senderID.hexEncodedString()}-${packet.timestamp}-${packet.type}-$digestPrefix"
        }

        /**
         * Attribute claimed sender vs link-bound peer (iOS packetContext).
         *
         * @param isRSR Request-Sync Response flag (wire Flags.IS_RSR / [BitchatPacket.isRSR]).
         */
        fun packetContext(
            packet: BitchatPacket,
            claimedSenderID: String,
            boundPeerID: String?,
            localPeerID: String,
            directAnnounceTTL: UByte,
            isRSR: Boolean = false,
        ): Result {
            if (claimedSenderID == localPeerID && !(isRSR && packet.ttl == 0u.toUByte())) {
                return Result.Failure(BleIngressRejection.SelfLoopback(packet.type))
            }

            if (boundPeerID != null && boundPeerID != claimedSenderID) {
                if (requiresDirectSenderBinding(packet)) {
                    return Result.Failure(
                        BleIngressRejection.DirectSenderMismatch(boundPeerID, claimedSenderID),
                    )
                }
                if (isDirectAnnounce(packet, directAnnounceTTL)) {
                    return Result.Success(
                        BleIngressPacketContext(
                            receivedFromPeerID = claimedSenderID,
                            validationPeerID = claimedSenderID,
                        ),
                    )
                }
            }

            val receivedFromPeerID = boundPeerID ?: claimedSenderID
            val validationPeerID = if (isRSR) receivedFromPeerID else claimedSenderID
            return Result.Success(
                BleIngressPacketContext(
                    receivedFromPeerID = receivedFromPeerID,
                    validationPeerID = validationPeerID,
                ),
            )
        }

        fun isDirectAnnounce(packet: BitchatPacket, directAnnounceTTL: UByte): Boolean =
            packet.type == MessageType.ANNOUNCE.value && packet.ttl == directAnnounceTTL

        private fun requiresDirectSenderBinding(packet: BitchatPacket): Boolean =
            packet.type == MessageType.REQUEST_SYNC.value

        sealed class Result {
            data class Success(val context: BleIngressPacketContext) : Result()
            data class Failure(val rejection: BleIngressRejection) : Result()
        }
    }
}
