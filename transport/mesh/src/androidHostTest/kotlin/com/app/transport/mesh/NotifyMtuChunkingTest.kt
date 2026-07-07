package com.app.transport.mesh

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BinaryProtocol
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.util.Collections

/**
 * Pins the MTU-aware notify path: at the BLE default ATT MTU (23) the Android stack
 * would silently truncate a notification to 20 bytes — the exact "(20 bytes)" parse
 * failure iOS logged for our REQUEST_SYNC. The broadcaster must instead chunk the frame
 * so the receiver's stream assembler (iOS NotificationStreamAssembler / our
 * BleFrameAssembler) reassembles it.
 */
@RunWith(RobolectricTestRunner::class)
class NotifyMtuChunkingTest {

    private val emissions: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
    private lateinit var scope: CoroutineScope
    private lateinit var tracker: BluetoothConnectionTracker
    private lateinit var broadcaster: BluetoothPacketBroadcaster

    private val serverValue = arrayOfNulls<ByteArray>(1)
    private lateinit var characteristic: BluetoothGattCharacteristic
    private lateinit var gattServer: BluetoothGattServer

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        tracker = BluetoothConnectionTracker(scope, mock())
        characteristic = mock {
            on { setValue(any<ByteArray>()) } doAnswer { inv ->
                serverValue[0] = (inv.arguments[0] as ByteArray).copyOf(); true
            }
        }
        gattServer = mock {
            on { notifyCharacteristicChanged(any(), any(), eq(false)) } doAnswer {
                emissions.add(serverValue[0]!!.copyOf()); true
            }
        }
        broadcaster = BluetoothPacketBroadcaster(
            connectionScope = scope,
            connectionTracker = tracker,
            fragmentManager = FragmentManager(),
            myPeerID = "0a0b0c0d0e0f1011",
            debugSettingsManager = mock(),
            transferProgressManager = TransferProgressManager(),
            gattServerProvider = { gattServer },
            characteristicProvider = { characteristic },
        )
    }

    @After
    fun tearDown() {
        broadcaster.shutdown()
        scope.cancel()
    }

    private fun requestSyncFrame(): ByteArray {
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.REQUEST_SYNC.value,
            senderID = ByteArray(8) { (it + 1).toByte() },
            recipientID = ByteArray(8) { 0x11 },
            timestamp = 1_700_000_000_000uL,
            payload = ByteArray(14) { it.toByte() },
            signature = ByteArray(64) { it.toByte() },
            ttl = 0u,
        )
        return BinaryProtocol.encode(packet, padding = false)!!
    }

    private fun subscribe(address: String, peerID: String): BluetoothDevice {
        val dev: BluetoothDevice = mock()
        whenever(dev.address).thenReturn(address)
        tracker.addSubscribedDevice(dev)
        tracker.addressPeerMap[address] = peerID
        return dev
    }

    @Test
    fun `default MTU 23 - frame is chunked to 20-byte notifications and reassembles`() {
        subscribe("AA:00:00:00:00:01", "1111111111111111")
        // No MTU exchange happened: tracker reports the BLE default of 23.

        val frame = requestSyncFrame()
        val packet = BitchatPacket.fromBinaryData(frame)!!
        assertTrue(broadcaster.sendToPeer("1111111111111111", RoutedPacket(packet)))

        assertTrue("Expected multiple chunks, got ${emissions.size}", emissions.size > 1)
        emissions.forEach { assertTrue("Chunk of ${it.size}B exceeds MTU-3=20", it.size <= 20) }

        val assembler = BleFrameAssembler()
        val frames = emissions.flatMap { assembler.append(it) }
        assertEquals(1, frames.size)
        assertArrayEquals(frame, frames[0])
        assertNotNull(BinaryProtocol.decode(frames[0]))
    }

    /**
     * Chunk runs of two concurrently sent frames must never interleave on one link:
     * interleaving desynchronizes the receiver's stream assembler into garbage frames
     * (observed on-device as bogus packets with corrupted sender IDs).
     */
    @Test
    fun `concurrent sends to one link keep each frame's chunks contiguous`() {
        subscribe("AA:00:00:00:00:03", "3333333333333333")
        // MTU stays 23 → every frame is chunked.

        val frameA = requestSyncFrame()
        val packetA = BitchatPacket.fromBinaryData(frameA)!!
        val packetB = packetA.copy(timestamp = packetA.timestamp + 1u)
        val frameB = BinaryProtocol.encode(packetB, padding = false)!!

        val threads = listOf(
            Thread { repeat(20) { broadcaster.sendToPeer("3333333333333333", RoutedPacket(packetA)) } },
            Thread { repeat(20) { broadcaster.sendToPeer("3333333333333333", RoutedPacket(packetB)) } },
        )
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Feeding the emissions in wire order into the assembler must yield exactly the
        // 40 sent frames — any interleaving would corrupt the stream.
        val assembler = BleFrameAssembler()
        val frames = emissions.flatMap { assembler.append(it) }
        assertEquals(40, frames.size)
        frames.forEach { frame ->
            assertTrue(frame.contentEquals(frameA) || frame.contentEquals(frameB))
        }
    }

    @Test
    fun `negotiated MTU 517 - frame goes out as a single notification`() {
        subscribe("AA:00:00:00:00:02", "2222222222222222")
        tracker.updateNegotiatedMtu("AA:00:00:00:00:02", 517)

        val frame = requestSyncFrame()
        val packet = BitchatPacket.fromBinaryData(frame)!!
        assertTrue(broadcaster.sendToPeer("2222222222222222", RoutedPacket(packet)))

        assertEquals(1, emissions.size)
        assertArrayEquals(frame, emissions[0])
    }
}
