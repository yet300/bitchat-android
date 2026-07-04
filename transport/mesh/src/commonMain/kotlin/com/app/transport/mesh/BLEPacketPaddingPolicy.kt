package com.app.transport.mesh

import com.app.transport.protocol.MessageType

/**
 * iOS-compatible BLE padding policy.
 *
 * Keep this aligned with iOS BLEOutboundPacketPolicy.padsBLEFrame(for:):
 * only Noise frames are padded over BLE. Public frames are sent unpadded so the
 * wire bytes match the iOS bitchat client.
 */
object BLEPacketPaddingPolicy {
    fun shouldPadForBLE(type: UByte): Boolean {
        return when (MessageType.fromValue(type)) {
            MessageType.NOISE_ENCRYPTED, MessageType.NOISE_HANDSHAKE -> true
            else -> false
        }
    }
}
