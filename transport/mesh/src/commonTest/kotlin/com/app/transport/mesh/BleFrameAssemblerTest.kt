package com.app.transport.mesh

import com.app.transport.protocol.BinaryProtocol
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The assembler must make inbound GATT values tolerant of MTU chunking: the iOS
 * reference client writes frames split to (ATT MTU − 3) bytes — 20-byte chunks at the
 * default MTU 23, which is exactly the "(20 bytes)" parse failure observed on-device —
 * and expects the receiver to reassemble by header-declared frame length.
 */
class BleFrameAssemblerTest {

    private var fakeNow = 0L
    private fun assembler() = BleFrameAssembler(nowMillis = { fakeNow })

    private fun frame(
        payload: ByteArray = "hello mesh".encodeToByteArray(),
        signed: Boolean = true,
        padding: Boolean = false,
        type: UByte = MessageType.REQUEST_SYNC.value,
    ): ByteArray {
        val packet = BitchatPacket(
            version = 1u,
            type = type,
            senderID = ByteArray(8) { (it + 1).toByte() },
            recipientID = ByteArray(8) { (0x11 * (it + 1)).toByte() },
            timestamp = 1_700_000_000_000uL,
            payload = payload,
            signature = if (signed) ByteArray(64) { it.toByte() } else null,
            ttl = 0u,
        )
        return BinaryProtocol.encode(packet, padding = padding)!!
    }

    @Test
    fun completeFramePassesThroughUnchanged() {
        val f = frame()
        val out = assembler().append(f)
        assertEquals(1, out.size)
        assertContentEquals(f, out[0])
        assertNotNull(BinaryProtocol.decode(out[0]))
    }

    @Test
    fun frameChunkedTo20BytesIsReassembled() {
        // Default ATT MTU 23 → 20-byte chunks, the exact on-device failure signature.
        val f = frame()
        val asm = assembler()
        val out = mutableListOf<ByteArray>()
        var i = 0
        while (i < f.size) {
            val end = minOf(i + 20, f.size)
            out += asm.append(f.copyOfRange(i, end))
            i = end
        }
        assertEquals(1, out.size)
        assertContentEquals(f, out[0])
        assertNotNull(BinaryProtocol.decode(out[0]))
    }

    @Test
    fun backToBackFramesInOneValueAreSplit() {
        val f1 = frame(payload = "first".encodeToByteArray())
        val f2 = frame(payload = "second".encodeToByteArray())
        val out = assembler().append(f1 + f2)
        assertEquals(2, out.size)
        assertContentEquals(f1, out[0])
        assertContentEquals(f2, out[1])
    }

    @Test
    fun chunkBoundaryInsideHeaderIsHandled() {
        val f = frame()
        val asm = assembler()
        val out = mutableListOf<ByteArray>()
        out += asm.append(f.copyOfRange(0, 5)) // shorter than the 14-byte header
        out += asm.append(f.copyOfRange(5, 30))
        out += asm.append(f.copyOfRange(30, f.size))
        assertEquals(1, out.size)
        assertContentEquals(f, out[0])
    }

    @Test
    fun paddedNoiseFrameChunkedIsReassembledAndPaddingDropped() {
        val padded = frame(type = MessageType.NOISE_ENCRYPTED.value, padding = true)
        val asm = assembler()
        val out = mutableListOf<ByteArray>()
        var i = 0
        while (i < padded.size) {
            val end = minOf(i + 20, padded.size)
            out += asm.append(padded.copyOfRange(i, end))
            i = end
        }
        assertEquals(1, out.size)
        val decoded = BinaryProtocol.decode(out[0])
        assertNotNull(decoded)
        assertEquals(MessageType.NOISE_ENCRYPTED.value, decoded.type)

        // The stream is clean again: a follow-up frame still parses.
        val next = frame(payload = "after padding".encodeToByteArray())
        val after = asm.append(next)
        assertEquals(1, after.size)
        assertContentEquals(next, after[0])
    }

    @Test
    fun stalledPartialFrameResetsAfterTimeoutAndRecovers() {
        val f = frame()
        val asm = assembler()
        assertTrue(asm.append(f.copyOfRange(0, 40)).isEmpty())

        // Past the stall window a fresh append triggers the reset...
        fakeNow += BleFrameAssembler.STALL_RESET_MS + 1
        assertTrue(asm.append(ByteArray(0)).isEmpty()) // no-op append, buffer still stalled
        val poke = asm.append(byteArrayOf(0x00))       // any traffic re-evaluates the stall
        assertTrue(poke.isEmpty())

        // ...and a complete new frame is processed cleanly afterwards.
        val out = asm.append(f)
        assertEquals(1, out.size)
        assertContentEquals(f, out[0])
    }

    @Test
    fun garbagePrefixIsSkippedUntilRealFrame() {
        val f = frame()
        val garbage = byteArrayOf(0x7F, 0x33, 0x00)
        val out = assembler().append(garbage + f)
        assertEquals(1, out.size)
        assertContentEquals(f, out[0])
    }
}
