package com.app.transport.model

import com.app.common.encoding.hexEncodedString
import com.app.transport.protocol.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceBurstPacketGoldenTest {
    private fun bytes(hex: String) = ByteArray(hex.length / 2) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private val burstId = bytes("0123456789abcdef")

    @Test
    fun `golden - voice frame message type is 0x29`() {
        assertEquals(0x29u.toUByte(), MessageType.VOICE_FRAME.value)
    }

    @Test
    fun `golden - all voice burst packet variants encode to the Swift bytes`() {
        val vectors = listOf(
            VoiceBurstPacket(burstId, 0u, VoiceBurstPacket.Kind.Start(VoiceBurstPacket.Codec.AAC_LC_16K_MONO)) to
                "0123456789abcdef00000101",
            VoiceBurstPacket(
                burstId,
                1u,
                VoiceBurstPacket.Kind.Frames(listOf(bytes("aabbcc"), bytes("7f"))),
            ) to "0123456789abcdef0001000003aabbcc00017f",
            VoiceBurstPacket(burstId, 2u, VoiceBurstPacket.Kind.End(1u, 1234u)) to
                "0123456789abcdef0002020001000004d2",
            VoiceBurstPacket(burstId, 3u, VoiceBurstPacket.Kind.Canceled) to
                "0123456789abcdef000304",
        )

        vectors.forEach { (packet, hex) ->
            assertEquals(hex, packet.encode().hexEncodedString())
            assertEquals(packet, VoiceBurstPacket.decode(bytes(hex)))
        }
    }

    @Test
    fun `decode rejects unknown codec and malformed frame lengths`() {
        assertNull(VoiceBurstPacket.decode(bytes("0123456789abcdef000001ff")))
        assertNull(VoiceBurstPacket.decode(bytes("0123456789abcdef0001000003aabb")))
    }
}
