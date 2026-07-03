package com.app.transport.protocol

import com.app.transport.model.IdentityAnnouncement
import com.app.transport.model.PrivateMessagePacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the wire-acceptance semantics of the two TLV decoders (arch review L3).
 *
 * The tolerance intentionally DIVERGES between the two decoders because iOS does
 * exactly the same (verified against upstream permissionlesstech/bitchat @ main,
 * bitchat/Protocols/Packets.swift):
 *  - AnnouncementPacket.decode skips unknown TLV types (forward-compatible);
 *  - PrivateMessagePacket.decode returns nil on any unknown TLV type (strict).
 * Do not "fix" the inconsistency without an owner decision + iOS-side change.
 */
class TlvTolerancePinTest {

    private val noiseKey = ByteArray(32) { 0xB }
    private val signingKey = ByteArray(32) { 0xA }

    private fun tlv(type: Int, value: ByteArray): ByteArray =
        byteArrayOf(type.toByte(), value.size.toByte()) + value

    @Test
    fun `announcement decoder skips unknown TLV and still decodes`() {
        val payload = tlv(0x01, "alice".encodeToByteArray()) +
            tlv(0x7F, byteArrayOf(1, 2, 3)) + // unknown type in the middle
            tlv(0x02, noiseKey) +
            tlv(0x03, signingKey)

        val decoded = IdentityAnnouncement.decode(payload)

        assertNotNull("Unknown TLV must be skipped (iOS parity)", decoded)
        assertEquals("alice", decoded!!.nickname)
    }

    @Test
    fun `announcement decoder skips directNeighbors TLV 0x04 that iOS emits`() {
        val payload = tlv(0x01, "bob".encodeToByteArray()) +
            tlv(0x02, noiseKey) +
            tlv(0x03, signingKey) +
            tlv(0x04, ByteArray(16)) // iOS directNeighbors (2 x 8-byte peer IDs)

        assertNotNull(
            "Announce with iOS directNeighbors TLV must still decode",
            IdentityAnnouncement.decode(payload)
        )
    }

    @Test
    fun `announcement decoder rejects truncated TLV value`() {
        val payload = tlv(0x01, "carol".encodeToByteArray()) +
            byteArrayOf(0x02, 32) // claims 32 bytes, none present

        assertNull(IdentityAnnouncement.decode(payload))
    }

    @Test
    fun `private message decoder rejects unknown TLV`() {
        val payload = tlv(0x00, "msg-1".encodeToByteArray()) +
            tlv(0x01, "hello".encodeToByteArray()) +
            tlv(0x7F, byteArrayOf(1)) // unknown type => whole packet rejected

        assertNull(
            "Unknown TLV must reject the whole private message (iOS parity)",
            PrivateMessagePacket.decode(payload)
        )
    }

    @Test
    fun `private message decoder accepts known TLVs`() {
        val payload = tlv(0x00, "msg-1".encodeToByteArray()) +
            tlv(0x01, "hello".encodeToByteArray())

        val decoded = PrivateMessagePacket.decode(payload)

        assertNotNull(decoded)
        assertEquals("msg-1", decoded!!.messageID)
        assertEquals("hello", decoded.content)
    }

    @Test
    fun `private message decoder rejects truncated TLV value`() {
        val payload = tlv(0x00, "msg-1".encodeToByteArray()) +
            byteArrayOf(0x01, 10, 'h'.code.toByte()) // claims 10 bytes, has 1

        assertNull(PrivateMessagePacket.decode(payload))
    }
}
