package com.app.transport.mesh

import com.app.transport.protocol.BitchatPacket

/**
 * First-line ingress validation before mesh security/signature work — port of iOS
 * `Services/BLE/BLEIngressPacketGuard.swift`.
 *
 * Composes [BleIngressLinkRegistry.packetContext] with payload checks (RSR gate, clock skew).
 */
object BleIngressPacketGuard {
    /** iOS BLEIngressPacketGuard default maxTimestampSkewMs. */
    const val DEFAULT_MAX_TIMESTAMP_SKEW_MS: Long = 120_000L

    sealed class Rejection {
        data class SelfLoopback(val packetType: UByte) : Rejection()
        data class DirectSenderMismatch(
            val boundPeerID: String,
            val claimedSenderID: String,
        ) : Rejection()
        data class InvalidRSR(val peerID: String) : Rejection()
        data class TimestampSkew(
            val peerID: String,
            val skewMs: Long,
            val maxSkewMs: Long,
        ) : Rejection()
    }

    sealed class EvaluateResult {
        data class Accept(val context: BleIngressPacketContext) : EvaluateResult()
        data class Reject(val rejection: Rejection) : EvaluateResult()
    }

    fun evaluate(
        packet: BitchatPacket,
        claimedSenderID: String,
        boundPeerID: String?,
        localPeerID: String,
        directAnnounceTTL: UByte,
        nowMs: Long,
        maxTimestampSkewMs: Long = DEFAULT_MAX_TIMESTAMP_SKEW_MS,
        isRSR: Boolean = false,
        isValidSyncResponse: (peerID: String) -> Boolean = { false },
    ): EvaluateResult {
        when (
            val contextResult = BleIngressLinkRegistry.packetContext(
                packet = packet,
                claimedSenderID = claimedSenderID,
                boundPeerID = boundPeerID,
                localPeerID = localPeerID,
                directAnnounceTTL = directAnnounceTTL,
                isRSR = isRSR,
            )
        ) {
            is BleIngressLinkRegistry.Companion.Result.Failure -> {
                return EvaluateResult.Reject(
                    when (val r = contextResult.rejection) {
                        is BleIngressRejection.SelfLoopback ->
                            Rejection.SelfLoopback(r.packetType)
                        is BleIngressRejection.DirectSenderMismatch ->
                            Rejection.DirectSenderMismatch(r.boundPeerID, r.claimedSenderID)
                    },
                )
            }
            is BleIngressLinkRegistry.Companion.Result.Success -> {
                val payloadResult = validatePayload(
                    packet = packet,
                    peerID = contextResult.context.validationPeerID,
                    nowMs = nowMs,
                    maxTimestampSkewMs = maxTimestampSkewMs,
                    isRSR = isRSR,
                    isValidSyncResponse = isValidSyncResponse,
                )
                return when (payloadResult) {
                    null -> EvaluateResult.Accept(contextResult.context)
                    else -> EvaluateResult.Reject(payloadResult)
                }
            }
        }
    }

    /**
     * @return null when payload is acceptable, otherwise a rejection.
     */
    fun validatePayload(
        packet: BitchatPacket,
        peerID: String,
        nowMs: Long,
        maxTimestampSkewMs: Long = DEFAULT_MAX_TIMESTAMP_SKEW_MS,
        isRSR: Boolean = false,
        isValidSyncResponse: (peerID: String) -> Boolean = { false },
    ): Rejection? {
        if (isRSR) {
            return if (isValidSyncResponse(peerID)) {
                null
            } else {
                Rejection.InvalidRSR(peerID)
            }
        }

        val packetTime = packet.timestamp.toLong()
        val skew = if (packetTime > nowMs) packetTime - nowMs else nowMs - packetTime
        if (skew > maxTimestampSkewMs) {
            return Rejection.TimestampSkew(peerID, skew, maxTimestampSkewMs)
        }
        return null
    }
}
