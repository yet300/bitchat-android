package com.app.transport.model

import com.app.common.encoding.hexEncodedString
import com.app.transport.protocol.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Hand-derived from the current iOS NostrCarrierPacket encoder. */
class NostrCarrierPacketGoldenTest {
    private val eventJson = "{\"id\":\"01\"}".encodeToByteArray()

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    @Test
    fun `golden - nostr carrier message type is 0x28`() {
        assertEquals(0x28u.toUByte(), MessageType.NOSTR_CARRIER.value)
    }

    @Test
    fun `golden - every current direction has its exact byte`() {
        val expected = listOf(
            NostrCarrierPacket.Direction.TO_GATEWAY to "01",
            NostrCarrierPacket.Direction.FROM_GATEWAY to "02",
            NostrCarrierPacket.Direction.TO_BRIDGE to "03",
            NostrCarrierPacket.Direction.FROM_BRIDGE to "04",
        )

        expected.forEach { (direction, directionHex) ->
            val packet = NostrCarrierPacket(direction, "u4pruy", eventJson)
            assertEquals(
                "010001${directionHex}02000675347072757903000b7b226964223a223031227d",
                packet.encode().hexEncodedString(),
            )
            assertEquals(packet, NostrCarrierPacket.decode(packet.encode()))
        }
    }

    @Test
    fun `decode skips complete unknown TLVs`() {
        val encoded = bytes(
            "01000101" +
                "7f0002dead" +
                "020006753470727579" +
                "03000b7b226964223a223031227d",
        )

        assertEquals(
            NostrCarrierPacket(NostrCarrierPacket.Direction.TO_GATEWAY, "u4pruy", eventJson),
            NostrCarrierPacket.decode(encoded),
        )
    }

    @Test
    fun `decode rejects malformed or incomplete fields`() {
        assertNull(NostrCarrierPacket.decode(byteArrayOf()))
        assertNull(NostrCarrierPacket.decode(bytes("01000177")))
        assertNull(NostrCarrierPacket.decode(bytes("01000101020006753470727579")))
        assertNull(NostrCarrierPacket.decode(bytes("0100010102000675347072757903000b7b22")))
        assertNull(NostrCarrierPacket.decode(bytes("0100010102000675347072757903000b7b226964223a223031227d7f")))
    }

    @Test
    fun `construction and decode enforce carrier bounds`() {
        assertNull(NostrCarrierPacket.orNull(NostrCarrierPacket.Direction.TO_GATEWAY, "", eventJson))
        assertNull(NostrCarrierPacket.orNull(NostrCarrierPacket.Direction.TO_GATEWAY, "u".repeat(13), eventJson))
        assertNull(NostrCarrierPacket.orNull(NostrCarrierPacket.Direction.TO_GATEWAY, "u4pruy", byteArrayOf()))
        assertNull(
            NostrCarrierPacket.orNull(
                NostrCarrierPacket.Direction.TO_GATEWAY,
                "u4pruy",
                ByteArray(NostrCarrierPacket.MAX_EVENT_JSON_BYTES + 1),
            ),
        )
        assertNotNull(
            NostrCarrierPacket.orNull(
                NostrCarrierPacket.Direction.TO_GATEWAY,
                "u".repeat(NostrCarrierPacket.MAX_GEOHASH_LENGTH),
                ByteArray(NostrCarrierPacket.MAX_EVENT_JSON_BYTES),
            ),
        )
    }
}
