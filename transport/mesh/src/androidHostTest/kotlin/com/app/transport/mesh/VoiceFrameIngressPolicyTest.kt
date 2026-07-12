package com.app.transport.mesh

import com.app.transport.model.VoiceBurstPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.SpecialRecipients
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceFrameIngressPolicyTest {
    private val nowMillis = 1_000_000L

    @Test
    fun `accepts a fresh signed broadcast voice burst only when outer verification passes`() {
        var verificationCalls = 0
        val accepted = VoiceFrameIngressPolicy.accepts(
            packet = voiceFrame(timestamp = nowMillis.toULong()),
            nowMillis = nowMillis,
        ) {
            verificationCalls += 1
            true
        }

        assertTrue(accepted)
        assertEquals(1, verificationCalls)
    }

    @Test
    fun `rejects missing signature stale directed and malformed voice frames without verifying`() {
        val rejected = listOf(
            voiceFrame(timestamp = nowMillis.toULong(), signature = null),
            voiceFrame(timestamp = (nowMillis - VoiceFrameIngressPolicy.MAX_AGE_MS - 1).toULong()),
            voiceFrame(timestamp = nowMillis.toULong(), recipientID = ByteArray(8) { 0x22 }),
            voiceFrame(timestamp = nowMillis.toULong(), payload = byteArrayOf(0x01)),
        )

        rejected.forEach { packet ->
            assertFalse(VoiceFrameIngressPolicy.accepts(packet, nowMillis) { error("must not verify") })
        }
    }

    @Test
    fun `rejects an outer signature that does not verify`() {
        assertFalse(VoiceFrameIngressPolicy.accepts(voiceFrame(timestamp = nowMillis.toULong()), nowMillis) { false })
    }

    private fun voiceFrame(
        timestamp: ULong,
        signature: ByteArray? = ByteArray(64) { 0x5A },
        recipientID: ByteArray? = SpecialRecipients.BROADCAST,
        payload: ByteArray = VoiceBurstPacket(
            burstId = ByteArray(VoiceBurstPacket.BURST_ID_SIZE) { 0x11 },
            seq = 0u,
            kind = VoiceBurstPacket.Kind.Start(VoiceBurstPacket.Codec.AAC_LC_16K_MONO),
        ).encode(),
    ) = BitchatPacket(
        type = MessageType.VOICE_FRAME.value,
        senderID = ByteArray(8) { 0x33 },
        recipientID = recipientID,
        timestamp = timestamp,
        payload = payload,
        signature = signature,
        ttl = 5u,
    )
}
