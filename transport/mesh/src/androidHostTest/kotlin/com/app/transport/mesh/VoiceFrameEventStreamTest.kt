package com.app.transport.mesh

import com.app.transport.voice.PublicVoiceFrame
import com.app.transport.voice.VoiceFrameEventStream
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class VoiceFrameEventStreamTest {
    @Test
    fun `emits accepted public voice frames to flow collectors without persistence`() = runTest {
        val stream = VoiceFrameEventStream()
        val awaiting = async { stream.frames.first() }
        runCurrent()
        val event = PublicVoiceFrame(
            peerId = "0123456789abcdef",
            payload = byteArrayOf(1, 2, 3),
            timestampMs = 1_000L,
        )

        stream.emit(event)

        val received = awaiting.await()
        assertEquals(event.peerId, received.peerId)
        assertContentEquals(event.payload, received.payload)
        assertEquals(event.timestampMs, received.timestampMs)
    }
}
