package com.app.transport.mesh

import com.app.transport.model.VoiceBurstPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.SpecialRecipients

/** Validation that must finish before a public live-voice frame can be relayed. */
internal object VoiceFrameIngressPolicy {
    const val MAX_AGE_MS = 30_000L

    fun accepts(
        packet: BitchatPacket,
        nowMillis: Long,
        verifyOuterSignature: () -> Boolean,
    ): Boolean {
        if (!isBroadcast(packet) || packet.signature == null || VoiceBurstPacket.decode(packet.payload) == null) {
            return false
        }
        if (nowMillis >= MAX_AGE_MS && packet.timestamp < (nowMillis - MAX_AGE_MS).toULong()) return false
        return verifyOuterSignature()
    }

    private fun isBroadcast(packet: BitchatPacket): Boolean =
        packet.recipientID == null || packet.recipientID.contentEquals(SpecialRecipients.BROADCAST)
}
