package com.app.transport.protocol

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/**
 * Encode-side guards: inputs the wire format cannot represent must fail loudly
 * (return null) instead of writing a silently truncated frame the decoder misparses.
 */
class BinaryProtocolGuardTest {

    private val sender = ByteArray(8) { (it + 1).toByte() }

    private fun packet(
        payload: ByteArray = "hello".toByteArray(),
        signature: ByteArray? = null,
        version: UByte = 1u,
    ) = BitchatPacket(
        version = version, type = MessageType.MESSAGE.value, senderID = sender,
        recipientID = null, timestamp = 1_700_000_000_000uL,
        payload = payload, signature = signature, ttl = 7u,
    )

    @Test
    fun `short signature is rejected`() {
        assertNull(BinaryProtocol.encode(packet(signature = ByteArray(63) { 1 })))
    }

    @Test
    fun `long signature is rejected`() {
        assertNull(BinaryProtocol.encode(packet(signature = ByteArray(65) { 1 })))
    }

    @Test
    fun `exact 64-byte signature still encodes`() {
        assertNotNull(BinaryProtocol.encode(packet(signature = ByteArray(64) { 1 })))
    }

    @Test
    fun `v1 payload over 65535 bytes is rejected`() {
        // High-entropy so compression cannot shrink it under the limit
        val oversized = Random(42).nextBytes(65_536)
        assertNull(BinaryProtocol.encode(packet(payload = oversized)))
    }

    @Test
    fun `v2 payload over 65535 bytes still encodes`() {
        val oversized = Random(42).nextBytes(65_536)
        assertNotNull(BinaryProtocol.encode(packet(payload = oversized, version = 2u)))
    }
}
