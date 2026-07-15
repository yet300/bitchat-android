package com.app.transport.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** P0.5: private live voice is NoisePayloadType.VOICE_FRAME = 0x08. */
class VoiceFrameNoisePayloadTest {

    @Test
    fun voiceFrameTypeIs0x08() {
        assertEquals(0x08u.toUByte(), NoisePayloadType.VOICE_FRAME.value)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val burst = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val encoded = NoisePayload(NoisePayloadType.VOICE_FRAME, burst).encode()
        assertEquals(0x08, encoded[0].toInt() and 0xFF)
        val decoded = assertNotNull(NoisePayload.decode(encoded))
        assertEquals(NoisePayloadType.VOICE_FRAME, decoded.type)
        assertContentEquals(burst, decoded.data)
    }
}
