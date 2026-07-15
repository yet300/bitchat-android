package com.app.transport.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Wire flag Flags.IS_RSR = 0x10 round-trip (iOS BinaryProtocol parity). */
class IsRsrFlagTest {

    private fun basePacket(isRSR: Boolean) = BitchatPacket(
        version = 1u,
        type = MessageType.MESSAGE.value,
        senderID = ByteArray(8) { 0x11 },
        recipientID = ByteArray(8) { 0x22 },
        timestamp = 1_700_000_000_000uL,
        payload = byteArrayOf(1, 2, 3),
        signature = null,
        ttl = 7u,
        isRSR = isRSR,
    )

    @Test
    fun encodeDecode_preservesIsRsr() {
        val encoded = BinaryProtocol.encode(basePacket(isRSR = true), padding = false)!!
        val decoded = BinaryProtocol.decode(encoded)!!
        assertTrue(decoded.isRSR)
        assertEquals(MessageType.MESSAGE.value, decoded.type)
    }

    @Test
    fun encodeDecode_defaultIsRsrFalse() {
        val encoded = BinaryProtocol.encode(basePacket(isRSR = false), padding = false)!!
        val decoded = BinaryProtocol.decode(encoded)!!
        assertFalse(decoded.isRSR)
    }

    @Test
    fun signingCopy_clearsIsRsr() {
        val packet = basePacket(isRSR = true)
        val forSign = packet.toBinaryDataForSigning()!!
        val decoded = BinaryProtocol.decode(forSign)!!
        assertFalse(decoded.isRSR, "isRSR must not be part of the signed representation")
    }
}
