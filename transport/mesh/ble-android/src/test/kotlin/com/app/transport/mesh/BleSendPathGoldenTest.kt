package com.app.transport.mesh

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import com.app.transport.MeshTrafficLog
import com.app.transport.model.FragmentPayload
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.SpecialRecipients
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.util.Collections

/**
 * GOLDEN characterization of the BLE send path (C1 unification anchor).
 *
 * Pins the CURRENT observable behavior of [BluetoothPacketBroadcaster] before the send core moves
 * to commonMain: (a) the exact bytes of every emitted frame, (b) the set and order of link
 * addresses each frame goes to, (c) relay/sender anti-loop, directed-send and source-routing
 * decisions, and (d) the outgoing-telemetry target sequence.
 *
 * These assertions MUST NOT change across the refactor: the unification changes WHERE the logic
 * lives, not the bytes, not the targets. If a case fails after refactoring, fix the code — do not
 * update the expectations.
 */
@RunWith(RobolectricTestRunner::class)
class BleSendPathGoldenTest {

    private companion object {
        const val MY_PEER = "0a0b0c0d0e0f1011"
        const val PEER_SRV_1 = "1111111111111111"
        const val PEER_SRV_2 = "2222222222222222"
        const val PEER_CLI_1 = "3333333333333333"
        const val ADDR_SRV_1 = "AA:00:00:00:00:01"
        const val ADDR_SRV_2 = "AA:00:00:00:00:02"
        const val ADDR_CLI_1 = "BB:00:00:00:00:01"
        const val ADDR_CLI_2 = "BB:00:00:00:00:02" // connected but no peer bound
        const val TIMEOUT_MS = 5000L
    }

    /** One observed radio write: which link address, which exact bytes. */
    private data class Emission(val address: String, val frame: ByteArray)

    private val emissions: MutableList<Emission> = Collections.synchronizedList(mutableListOf())
    private val telemetryTargets: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    private lateinit var scope: CoroutineScope
    private lateinit var tracker: BluetoothConnectionTracker
    private lateinit var broadcaster: BluetoothPacketBroadcaster
    private lateinit var gattServer: BluetoothGattServer
    private lateinit var serverCharacteristic: BluetoothGattCharacteristic

    private val trafficLog = object : MeshTrafficLog {
        override fun setNicknameResolver(resolver: (String) -> String?) = Unit
        override fun logIncoming(
            packet: BitchatPacket,
            fromPeerID: String,
            fromNickname: String?,
            fromDeviceAddress: String?,
            myPeerID: String,
        ) = Unit

        override fun logOutgoing(
            packetType: String,
            toPeerID: String?,
            toNickname: String?,
            toDeviceAddress: String?,
            previousHopPeerID: String?,
            packetVersion: UByte,
            routeInfo: String?,
        ) {
            telemetryTargets.add(toDeviceAddress)
        }

        override fun logIncomingPacket(
            senderPeerID: String,
            senderNickname: String?,
            messageType: String,
            viaDeviceId: String?,
        ) = Unit

        override fun logPacketRelayDetailed(
            packetType: String,
            senderPeerID: String?,
            senderNickname: String?,
            fromPeerID: String?,
            fromNickname: String?,
            fromDeviceAddress: String?,
            toPeerID: String?,
            toNickname: String?,
            toDeviceAddress: String?,
            ttl: UByte?,
            isRelay: Boolean,
            packetVersion: UByte,
            routeInfo: String?,
        ) = Unit

        override fun logPeerConnection(peerID: String, nickname: String, deviceID: String, isInbound: Boolean) = Unit
        override fun logPeerDisconnection(peerID: String, nickname: String, deviceID: String) = Unit
        override fun addScanResult(scanResult: com.app.transport.debug.DebugScanResult) = Unit
    }

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        tracker = BluetoothConnectionTracker(scope, mock())

        // GATT server side: one shared characteristic; capture value at notify time (it is reused).
        val serverValue = arrayOfNulls<ByteArray>(1)
        serverCharacteristic = mock {
            on { setValue(any<ByteArray>()) } doAnswer { inv ->
                serverValue[0] = (inv.arguments[0] as ByteArray).copyOf(); true
            }
        }
        gattServer = mock {
            on { notifyCharacteristicChanged(any(), any(), eq(false)) } doAnswer { inv ->
                val device = inv.arguments[0] as BluetoothDevice
                emissions.add(Emission(device.address, serverValue[0]!!.copyOf()))
                true
            }
        }

        broadcaster = BluetoothPacketBroadcaster(
            connectionScope = scope,
            connectionTracker = tracker,
            fragmentManager = FragmentManager(),
            myPeerID = MY_PEER,
            debugSettingsManager = trafficLog,
            transferProgressManager = TransferProgressManager(),
            gattServerProvider = { gattServer },
            characteristicProvider = { serverCharacteristic },
        )
    }

    @After
    fun tearDown() {
        broadcaster.shutdown()
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // Topology helpers
    // ------------------------------------------------------------------

    private fun addServerNeighbor(address: String, peerID: String?): BluetoothDevice {
        val dev: BluetoothDevice = mock()
        whenever(dev.address).thenReturn(address)
        tracker.addSubscribedDevice(dev)
        if (peerID != null) tracker.addressPeerMap[address] = peerID
        return dev
    }

    private fun addClientNeighbor(address: String, peerID: String?): BluetoothConnectionTracker.DeviceConnection {
        val dev: BluetoothDevice = mock()
        whenever(dev.address).thenReturn(address)
        val value = arrayOfNulls<ByteArray>(1)
        val char: BluetoothGattCharacteristic = mock {
            on { setValue(any<ByteArray>()) } doAnswer { inv ->
                value[0] = (inv.arguments[0] as ByteArray).copyOf(); true
            }
        }
        val gatt: BluetoothGatt = mock {
            on { writeCharacteristic(anyOrNull<BluetoothGattCharacteristic>()) } doAnswer {
                emissions.add(Emission(address, value[0]!!.copyOf()))
                true
            }
        }
        val conn = BluetoothConnectionTracker.DeviceConnection(
            device = dev, gatt = gatt, characteristic = char, isClient = true, peerID = peerID,
        )
        tracker.addDeviceConnection(address, conn)
        if (peerID != null) tracker.addressPeerMap[address] = peerID
        return conn
    }

    private fun defaultTopology() {
        addServerNeighbor(ADDR_SRV_1, PEER_SRV_1)
        addServerNeighbor(ADDR_SRV_2, PEER_SRV_2)
        addClientNeighbor(ADDR_CLI_1, PEER_CLI_1)
        addClientNeighbor(ADDR_CLI_2, null)
    }

    // ------------------------------------------------------------------
    // Packet helpers
    // ------------------------------------------------------------------

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte() }

    private fun packet(
        type: UByte = MessageType.MESSAGE.value,
        sender: String = "4444444444444444", // not a direct neighbor by default
        recipient: ByteArray? = SpecialRecipients.BROADCAST,
        payload: ByteArray = "golden payload".encodeToByteArray(),
        route: List<ByteArray>? = null,
        version: UByte = if (route != null) 2u else 1u,
    ) = BitchatPacket(
        version = version,
        type = type,
        senderID = hexToBytes(sender),
        recipientID = recipient,
        timestamp = 1_700_000_000_000uL,
        payload = payload,
        ttl = 5u,
        route = route,
    )

    private fun expectedFrame(p: BitchatPacket): ByteArray =
        p.toBinaryData(padding = BLEPacketPaddingPolicy.shouldPadForBLE(p.type))!!

    private fun awaitEmissions(count: Int) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (emissions.size < count && System.currentTimeMillis() < deadline) Thread.sleep(5)
        // settle: catch over-sends (extra frames = target-set regression)
        Thread.sleep(50)
        assertEquals("emitted frame count", count, emissions.size)
    }

    private fun broadcast(routed: RoutedPacket) =
        broadcaster.broadcastPacket(routed)

    // ------------------------------------------------------------------
    // GOLDEN cases
    // ------------------------------------------------------------------

    @Test
    fun `broadcast goes to all neighbors, servers first, exact bytes, no padding for MESSAGE`() {
        defaultTopology()
        val p = packet()
        val expected = expectedFrame(p)

        broadcast(RoutedPacket(p))
        awaitEmissions(4)

        val snapshot = emissions.toList()
        // Server neighbors first, in subscription order.
        assertEquals(listOf(ADDR_SRV_1, ADDR_SRV_2), snapshot.take(2).map { it.address })
        // Then client neighbors (map order not guaranteed — compare as a set).
        assertEquals(setOf(ADDR_CLI_1, ADDR_CLI_2), snapshot.drop(2).map { it.address }.toSet())
        // Every frame is byte-identical to the unpadded BinaryProtocol encoding.
        snapshot.forEach { assertArrayEquals(expected, it.frame) }
        // Telemetry logged one outgoing entry per delivered frame, same addresses.
        assertEquals(snapshot.map { it.address }, telemetryTargets.toList())
    }

    @Test
    fun `anti-loop - relayer address and original sender are skipped`() {
        defaultTopology()
        val p = packet(sender = PEER_CLI_1) // sender is the peer bound to ADDR_CLI_1
        broadcast(RoutedPacket(p, peerID = PEER_CLI_1, relayAddress = ADDR_SRV_1))
        awaitEmissions(2)

        // ADDR_SRV_1 skipped (relayer), ADDR_CLI_1 skipped (sender's own link).
        assertEquals(setOf(ADDR_SRV_2, ADDR_CLI_2), emissions.map { it.address }.toSet())
    }

    @Test
    fun `directed packet to connected neighbor goes only to that link`() {
        defaultTopology()
        val p = packet(recipient = hexToBytes(PEER_SRV_2))
        broadcast(RoutedPacket(p))
        awaitEmissions(1)

        assertEquals(ADDR_SRV_2, emissions[0].address)
        assertArrayEquals(expectedFrame(p), emissions[0].frame)
    }

    @Test
    fun `directed packet to unknown recipient falls back to full broadcast`() {
        defaultTopology()
        val p = packet(recipient = hexToBytes("9999999999999999"))
        broadcast(RoutedPacket(p))
        awaitEmissions(4)

        assertEquals(
            setOf(ADDR_SRV_1, ADDR_SRV_2, ADDR_CLI_1, ADDR_CLI_2),
            emissions.map { it.address }.toSet(),
        )
    }

    @Test
    fun `source-routed own packet goes only to connected first hop`() {
        defaultTopology()
        val p = packet(
            sender = MY_PEER,
            recipient = hexToBytes("9999999999999999"),
            route = listOf(hexToBytes(PEER_CLI_1), hexToBytes("9999999999999999")),
        )
        broadcast(RoutedPacket(p))
        awaitEmissions(1)

        assertEquals(ADDR_CLI_1, emissions[0].address)
        assertArrayEquals(expectedFrame(p), emissions[0].frame)
    }

    @Test
    fun `source-routed own packet with unreachable first hop falls back to broadcast`() {
        defaultTopology()
        val p = packet(
            sender = MY_PEER,
            recipient = hexToBytes("9999999999999999"),
            route = listOf(hexToBytes("7777777777777777")),
        )
        broadcast(RoutedPacket(p))
        awaitEmissions(4)

        assertEquals(
            setOf(ADDR_SRV_1, ADDR_SRV_2, ADDR_CLI_1, ADDR_CLI_2),
            emissions.map { it.address }.toSet(),
        )
    }

    @Test
    fun `relayed packet with route is NOT source-routed - only originator uses the route`() {
        defaultTopology()
        // Same route, but senderID != myPeerID -> normal directed/broadcast logic applies.
        val p = packet(
            sender = PEER_SRV_1,
            recipient = hexToBytes(PEER_SRV_2),
            route = listOf(hexToBytes(PEER_CLI_1)),
        )
        broadcast(RoutedPacket(p))
        awaitEmissions(1)
        assertEquals(ADDR_SRV_2, emissions[0].address)
    }

    @Test
    fun `noise frames are padded on the wire, exact padded bytes`() {
        defaultTopology()
        val p = packet(type = MessageType.NOISE_ENCRYPTED.value, recipient = hexToBytes(PEER_SRV_1))
        broadcast(RoutedPacket(p))
        awaitEmissions(1)

        val padded = expectedFrame(p)
        assertArrayEquals(padded, emissions[0].frame)
        // And padding actually changed the frame vs the unpadded encoding.
        assertNotEquals(p.toBinaryData(padding = false)!!.size, emissions[0].frame.size)
    }

    @Test
    fun `oversize packet is fragmented - ordered fragments reassemble to the original frame`() {
        addServerNeighbor(ADDR_SRV_1, PEER_SRV_1) // single neighbor => deterministic order
        val p = packet(payload = ByteArray(1400) { (it % 251).toByte() })
        val originalFrame = p.toBinaryData()!!

        broadcast(RoutedPacket(p))
        val expectedFragments = FragmentManager().createFragments(p).size
        assertTrue("test packet must fragment", expectedFragments > 1)
        awaitEmissions(expectedFragments)

        val payloads = emissions.map { e ->
            val fp = BitchatPacket.fromBinaryData(e.frame)!!
            assertEquals(MessageType.FRAGMENT.value, fp.type)
            FragmentPayload.decode(fp.payload)!!
        }
        // In-order indices, one shared fragmentID, original type preserved.
        assertEquals((0 until expectedFragments).toList(), payloads.map { it.index })
        assertEquals(1, payloads.map { it.fragmentID.toList() }.toSet().size)
        payloads.forEach { assertEquals(MessageType.MESSAGE.value, it.originalType) }
        // Byte-exact reassembly of the original unfragmented frame.
        val reassembled = payloads.sortedBy { it.index }
            .fold(ByteArray(0)) { acc, f -> acc + f.data }
        assertArrayEquals(originalFrame, reassembled)
    }

    @Test
    fun `sendToPeer prefers the server link when peer is reachable both ways`() {
        addServerNeighbor(ADDR_SRV_1, PEER_SRV_1)
        addClientNeighbor(ADDR_CLI_1, PEER_SRV_1) // same peer on a client link too
        val p = packet(recipient = hexToBytes(PEER_SRV_1))

        val sent = broadcaster.sendToPeer(PEER_SRV_1, RoutedPacket(p))

        assertTrue(sent)
        assertEquals(1, emissions.size)
        assertEquals(ADDR_SRV_1, emissions[0].address)
        assertArrayEquals(expectedFrame(p), emissions[0].frame)
    }

    @Test
    fun `sendToPeer to unknown peer sends nothing and returns false`() {
        defaultTopology()
        val p = packet(recipient = hexToBytes("9999999999999999"))
        val sent = broadcaster.sendToPeer("9999999999999999", RoutedPacket(p))
        assertFalse(sent)
        assertEquals(0, emissions.size)
    }
}
