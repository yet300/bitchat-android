package com.app.transport.mesh

import com.app.common.encoding.hexEncodedString
import com.app.transport.crypto.Sha256
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.SpecialRecipients
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GOLDEN characterization of [FragmentingPacketSender] — the commonMain fragmentation/transfer
 * loop that the C1 unification routes all bearers (Wi-Fi Aware, Android BLE, CoreBluetooth)
 * through. Pins: fragment ordering, FRAGMENT passthrough, transferId derivation for
 * FILE_TRANSFER, stop-on-failure, and cancel semantics. Must not change across the refactor.
 */
class FragmentingPacketSenderGoldenTest {

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte() }

    private fun packet(
        type: UByte = MessageType.MESSAGE.value,
        payload: ByteArray = "hello".encodeToByteArray(),
    ) = BitchatPacket(
        version = 1u,
        type = type,
        senderID = hexToBytes("1122334455667788"),
        recipientID = SpecialRecipients.BROADCAST,
        timestamp = 1_700_000_000_000uL,
        payload = payload,
        ttl = 5u,
        route = null,
    )

    @Test
    fun `small packet passes through as a single untouched send`() = runTest {
        val sender = FragmentingPacketSender(this, FragmentManager(), TransferProgressManager(), "test", 0L)
        val seen = mutableListOf<RoutedPacket>()
        val p = packet()

        val ok = sender.send(RoutedPacket(p), "golden") { seen.add(it); true }
        advanceUntilIdle()

        assertTrue(ok)
        assertEquals(1, seen.size)
        assertEquals(p, seen[0].packet)
        assertNull(seen[0].transferId)
    }

    @Test
    fun `FRAGMENT-typed packet is never re-fragmented`() = runTest {
        val sender = FragmentingPacketSender(this, FragmentManager(), TransferProgressManager(), "test", 0L)
        val seen = mutableListOf<RoutedPacket>()
        val p = packet(type = MessageType.FRAGMENT.value, payload = ByteArray(1400))

        sender.send(RoutedPacket(p), "golden") { seen.add(it); true }
        advanceUntilIdle()

        assertEquals(1, seen.size)
        assertEquals(p, seen[0].packet)
    }

    @Test
    fun `oversize packet fans out into ordered FRAGMENT sends`() = runTest {
        val sender = FragmentingPacketSender(this, FragmentManager(), TransferProgressManager(), "test", 0L)
        val seen = mutableListOf<RoutedPacket>()
        val p = packet(payload = ByteArray(1400) { (it % 251).toByte() })

        val ok = sender.send(RoutedPacket(p), "golden") { seen.add(it); true }
        advanceUntilIdle()

        assertTrue(ok)
        assertTrue(seen.size > 1)
        seen.forEach { assertEquals(MessageType.FRAGMENT.value, it.packet.type) }
        // Byte-exact: concatenated fragment data re-forms the original encoded frame.
        val reassembled = seen
            .map { com.app.transport.model.FragmentPayload.decode(it.packet.payload)!! }
            .also { fps -> assertEquals(fps.indices.toList(), fps.map { it.index }) }
            .fold(ByteArray(0)) { acc, f -> acc + f.data }
        assertEquals(p.toBinaryData()!!.hexEncodedString(), reassembled.hexEncodedString())
    }

    @Test
    fun `FILE_TRANSFER derives its transferId from the payload sha256`() = runTest {
        val sender = FragmentingPacketSender(this, FragmentManager(), TransferProgressManager(), "test", 0L)
        val seen = mutableListOf<RoutedPacket>()
        val payload = ByteArray(64) { it.toByte() }
        val p = packet(type = MessageType.FILE_TRANSFER.value, payload = payload)

        sender.send(RoutedPacket(p), "golden") { seen.add(it); true }
        advanceUntilIdle()

        assertEquals(Sha256.digest(payload).hexEncodedString(), seen[0].transferId)
    }

    @Test
    fun `fragmented send stops after a failed fragment`() = runTest {
        val sender = FragmentingPacketSender(this, FragmentManager(), TransferProgressManager(), "test", 0L)
        var calls = 0
        val p = packet(payload = ByteArray(1400) { (it % 251).toByte() })

        sender.send(RoutedPacket(p), "golden") { calls += 1; calls < 2 }
        advanceUntilIdle()

        assertEquals(2, calls) // second send failed -> loop stopped
    }

    @Test
    fun `cancelTransfer stops an in-flight fragmented transfer`() = runTest {
        val sender = FragmentingPacketSender(this, FragmentManager(), TransferProgressManager(), "test", 1000L)
        var calls = 0
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val p = packet(type = MessageType.FILE_TRANSFER.value, payload = payload)
        val transferId = Sha256.digest(payload).hexEncodedString()

        sender.send(RoutedPacket(p), "golden") { calls += 1; true }
        testScheduler.advanceTimeBy(1L); testScheduler.runCurrent()
        val cancelled = sender.cancelTransfer(transferId)
        advanceUntilIdle()

        assertTrue(cancelled)
        assertTrue("expected partial delivery, got $calls") { calls >= 1 }
        assertFalse(sender.cancelTransfer(transferId)) // already gone
    }
}
