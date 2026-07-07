@file:OptIn(ExperimentalForeignApi::class, ExperimentalUuidApi::class)

package com.app.transport.mesh

import co.touchlab.stately.collections.ConcurrentMutableMap
import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import com.app.common.AppDispatchers
import com.app.common.utils.Log
import com.app.common.encoding.toHexString
import com.app.transport.MeshConstants
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import platform.CoreBluetooth.CBATTErrorSuccess
import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBAttributePermissionsReadable
import platform.CoreBluetooth.CBAttributePermissionsWriteable
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicProperties
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBMutableService
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.darwin.NSObject
import kotlin.uuid.ExperimentalUuidApi

/**
 * Dual-role CoreBluetooth orchestrator — the Apple counterpart of the Android
 * [BluetoothConnectionManager] (+ its GATT server/client managers and packet broadcaster).
 *
 * Runs both roles simultaneously, like the reference iOS bitchat:
 *   - PERIPHERAL ([CBPeripheralManager]): advertises the mesh service UUID, hosts the GATT service
 *     with a notify+write characteristic, and pushes packets to subscribed centrals via notify
 *     (updateValue).
 *   - CENTRAL ([CBCentralManager]): scans for the mesh service UUID, connects, subscribes to the
 *     characteristic, and pushes packets via write-without-response.
 *
 * The GATT service/characteristic UUIDs come from the shared commonMain [MeshConstants.Mesh.Gatt]
 * (byte-identical to Android/iOS). Fragmentation, padding and packet (de)serialization are reused
 * verbatim from commonMain (FragmentManager, BLEPacketPaddingPolicy, BitchatPacket) — not
 * reimplemented — so the wire bytes match the other platforms.
 *
 * [linkAddress] is the opaque NSUUID string of the remote CBPeripheral (client side) or CBCentral
 * (server side); it is never parsed (it rotates between devices on iOS).
 */
internal class CoreBluetoothConnectionManager(
    private val myPeerID: String,
    private val fragmentManager: FragmentManager?,
    private val transferProgressManager: TransferProgressManager,
    dispatchers: AppDispatchers = AppDispatchers(),
) : BearerTransport {

    override var delegate: BearerTransportDelegate? = null

    private companion object {
        const val TAG = "CoreBluetoothConnectionManager"
        // CoreBluetooth has no MTU-request API: the central learns the negotiated ATT MTU and
        // exposes it via maximumWriteValueLengthForType; fragmentation uses the commonMain cap.
    }

    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())

    private val serviceCbUuid: CBUUID = CBUUID.UUIDWithString(MeshConstants.Mesh.Gatt.SERVICE_UUID.toString())
    private val characteristicCbUuid: CBUUID = CBUUID.UUIDWithString(MeshConstants.Mesh.Gatt.CHARACTERISTIC_UUID.toString())

    // Client (central) state, keyed by CBPeripheral.identifier.UUIDString.
    private val connectedPeripherals = ConcurrentMutableMap<String, CBPeripheral>()
    private val peripheralCharacteristics = ConcurrentMutableMap<String, CBCharacteristic>()
    private val pendingPeripherals = ConcurrentMutableMap<String, CBPeripheral>()

    // Server (peripheral) state, keyed by CBCentral.identifier.UUIDString.
    private val subscribedCentrals = ConcurrentMutableMap<String, CBCentral>()

    // Per-link reassembly of chunked inbound values: peers write/notify frames split to
    // their link's usable MTU, and the receiver reassembles by the header-declared frame
    // length (iOS reference client parity: NotificationStreamAssembler/BLEInboundWriteBuffer).
    private val frameAssemblers = ConcurrentMutableMap<String, BleFrameAssembler>()

    // Serializes frame emission per link: a frame chunked to the link's usable MTU must
    // hit the wire as one contiguous chunk run — interleaved chunks from concurrent
    // callers (queued broadcast consumer vs. synchronous sendToPeer) desynchronize the
    // receiver's stream assembler into garbage frames.
    private val emissionLocks = ConcurrentMutableMap<String, Lock>()

    private fun emissionLock(linkAddress: String) = emissionLocks.getOrPut(linkAddress) { Lock() }

    /** linkAddress -> logical peerID, owned by the bearer via [BleBearer.bindPeer]. */
    override val addressPeerMap = ConcurrentMutableMap<String, String>()

    private var mutableCharacteristic: CBMutableCharacteristic? = null

    @Suppress("unused") // retained so the delegate objects are not deallocated
    private val centralDelegate = CentralDelegate()
    private val peripheralDelegate = PeripheralManagerDelegate()

    private val centralManager = CBCentralManager(centralDelegate, null)
    private val peripheralManager = CBPeripheralManager(peripheralDelegate, null)

    private var active = false

    // -----------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------

    override fun startServices(): Boolean {
        active = true
        // CoreBluetooth only allows scan/advertise once the managers report poweredOn; the
        // delegates kick those off (or do so now if the managers are already powered on).
        if (centralManager.state == CBManagerStatePoweredOn) startScan()
        if (peripheralManager.state == CBManagerStatePoweredOn) startAdvertising()
        return true
    }

    override fun stopServices() {
        active = false
        try { centralManager.stopScan() } catch (_: Exception) {}
        try { peripheralManager.stopAdvertising() } catch (_: Exception) {}
        try { peripheralManager.removeAllServices() } catch (_: Exception) {}
        connectedPeripherals.values.forEach { p ->
            try { centralManager.cancelPeripheralConnection(p) } catch (_: Exception) {}
        }
        connectedPeripherals.clear()
        peripheralCharacteristics.clear()
        pendingPeripherals.clear()
        subscribedCentrals.clear()
        frameAssemblers.clear()
    }

    fun shutdown() {
        stopServices()
        sendCore.shutdown()
        scope.cancel()
    }

    private fun startScan() {
        if (!active) return
        Log.d(TAG, "Central: scanning for mesh service")
        centralManager.scanForPeripheralsWithServices(listOf(serviceCbUuid), null)
    }

    private fun startAdvertising() {
        if (!active) return
        setupService()
        Log.d(TAG, "Peripheral: advertising mesh service")
        peripheralManager.startAdvertising(
            mapOf<Any?, Any?>(CBAdvertisementDataServiceUUIDsKey to listOf(serviceCbUuid)),
        )
    }

    private fun setupService() {
        val properties: CBCharacteristicProperties =
            CBCharacteristicPropertyRead or
                CBCharacteristicPropertyWrite or
                CBCharacteristicPropertyWriteWithoutResponse or
                CBCharacteristicPropertyNotify
        val permissions = CBAttributePermissionsReadable or CBAttributePermissionsWriteable
        val char = CBMutableCharacteristic(
            type = characteristicCbUuid,
            properties = properties,
            value = null,
            permissions = permissions,
        )
        mutableCharacteristic = char
        val service = CBMutableService(type = serviceCbUuid, primary = true)
        service.setCharacteristics(listOf(char))
        peripheralManager.removeAllServices()
        peripheralManager.addService(service)
    }

    // -----------------------------------------------------------------
    // Send — shared BleSendCore (C1); only the raw CB writes below are Apple-specific.
    // Source routing stays disabled here to preserve the pre-unification target set
    // (the Apple bearer never source-routed); the owner can enable it deliberately.
    // -----------------------------------------------------------------

    private val sendCore = BleSendCore(
        scope = scope,
        fragmentManager = fragmentManager,
        transferProgressManager = transferProgressManager,
        myPeerID = myPeerID,
        radio = RadioLink(),
        trafficLog = null,
        sourceRoutingEnabled = false,
        logTag = TAG,
    )

    override fun broadcastPacket(routed: RoutedPacket) {
        if (!active) return
        sendCore.broadcastPacket(routed)
    }

    override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean {
        if (!active) return false
        return sendCore.sendToPeer(peerID, routed)
    }

    override fun cancelTransfer(transferId: String): Boolean = sendCore.cancelTransfer(transferId)

    private inner class RadioLink : BleRadioLink {
        override fun neighbors(): List<BleNeighbor> =
            subscribedCentrals.keys.map { addr ->
                BleNeighbor(addr, isClient = false, peerID = addressPeerMap.get(addr))
            } + connectedPeripherals.keys.map { addr ->
                BleNeighbor(addr, isClient = true, peerID = addressPeerMap.get(addr))
            }

        override fun peerForAddress(linkAddress: String): String? = addressPeerMap.get(linkAddress)

        override fun writeToNeighbor(neighbor: BleNeighbor, frame: ByteArray): Boolean =
            if (neighbor.isClient) {
                val peripheral = connectedPeripherals.get(neighbor.linkAddress) ?: return false
                writeToPeripheral(neighbor.linkAddress, peripheral, frame)
            } else {
                val central = subscribedCentrals.get(neighbor.linkAddress) ?: return false
                notifyCentral(central, frame)
            }
    }

    private fun notifyCentral(central: CBCentral, data: ByteArray): Boolean {
        val char = mutableCharacteristic ?: return false
        return emissionLock(central.identifier.UUIDString).withLock { try {
            // CoreBluetooth truncates an update larger than the central's negotiated
            // maximumUpdateValueLength — chunk instead; receivers reassemble by the
            // frame length declared in the header.
            val maxChunk = central.maximumUpdateValueLength.toInt().coerceAtLeast(1)
            if (data.size <= maxChunk) {
                return peripheralManager.updateValue(data.toNSData(), char, listOf(central))
            }
            Log.d(TAG, "Chunking ${data.size}B notification by $maxChunk bytes")
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + maxChunk, data.size)
                val chunk = data.copyOfRange(offset, end)
                if (!peripheralManager.updateValue(chunk.toNSData(), char, listOf(central))) {
                    // Transmit queue full mid-frame: the frame is lost; the receiver's
                    // stall reset cleans the stream.
                    Log.w(TAG, "notifyCentral chunk failed at $offset/${data.size}")
                    return false
                }
                offset = end
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "notifyCentral failed: ${e.message}")
            false
        } }
    }

    private fun writeToPeripheral(addr: String, peripheral: CBPeripheral, data: ByteArray): Boolean {
        val char = peripheralCharacteristics.get(addr) ?: return false
        return emissionLock(addr).withLock { try {
            // Writes above maximumWriteValueLengthForType are dropped/truncated by the
            // stack — chunk to the link's usable size (ATT MTU − 3 for withoutResponse).
            val maxChunk = peripheral
                .maximumWriteValueLengthForType(CBCharacteristicWriteWithoutResponse)
                .toInt()
                .coerceAtLeast(1)
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + maxChunk, data.size)
                peripheral.writeValue(
                    data.copyOfRange(offset, end).toNSData(),
                    char,
                    CBCharacteristicWriteWithoutResponse,
                )
                offset = end
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "writeToPeripheral failed: ${e.message}")
            false
        } }
    }

    // -----------------------------------------------------------------
    // Incoming
    // -----------------------------------------------------------------

    private fun handleIncoming(value: ByteArray, linkAddress: String) {
        // A value may be a complete frame or an MTU-sized chunk of a larger frame
        // (e.g. a 20-byte slice at the default ATT MTU 23) — reassemble either way.
        val assembler = frameAssemblers.getOrPut(linkAddress) { BleFrameAssembler() }
        for (frame in assembler.append(value)) {
            val packet = BitchatPacket.fromBinaryData(frame)
            if (packet == null) {
                Log.w(TAG, "Failed to parse packet from $linkAddress (${frame.size} bytes)")
                continue
            }
            val peerID = packet.senderID.take(8).toByteArray().toHexString()
            if (peerID == myPeerID) continue
            delegate?.onPacketReceived(packet, peerID, linkAddress)
        }
    }

    // -----------------------------------------------------------------
    // Central (client) delegate
    // -----------------------------------------------------------------

    private inner class CentralDelegate : NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (central.state == CBManagerStatePoweredOn && active) startScan()
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            val addr = didDiscoverPeripheral.identifier.UUIDString
            if (connectedPeripherals.get(addr) != null || pendingPeripherals.get(addr) != null) return
            pendingPeripherals.put(addr, didDiscoverPeripheral) // retain during connection
            central.connectPeripheral(didDiscoverPeripheral, null)
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            didConnectPeripheral.delegate = this
            didConnectPeripheral.discoverServices(listOf(serviceCbUuid))
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            val addr = didDisconnectPeripheral.identifier.UUIDString
            connectedPeripherals.remove(addr)
            peripheralCharacteristics.remove(addr)
            pendingPeripherals.remove(addr)
            addressPeerMap.remove(addr)
            frameAssemblers.remove(addr)
            delegate?.onDeviceDisconnected(addr)
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didFailToConnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            pendingPeripherals.remove(didFailToConnectPeripheral.identifier.UUIDString)
        }

        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            val service = (peripheral.services as? List<*>)
                ?.filterIsInstance<CBService>()
                ?.firstOrNull { it.UUID == serviceCbUuid } ?: return
            peripheral.discoverCharacteristics(listOf(characteristicCbUuid), service)
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?,
        ) {
            val char = (didDiscoverCharacteristicsForService.characteristics as? List<*>)
                ?.filterIsInstance<CBCharacteristic>()
                ?.firstOrNull { it.UUID == characteristicCbUuid } ?: return
            val addr = peripheral.identifier.UUIDString
            connectedPeripherals.put(addr, peripheral)
            peripheralCharacteristics.put(addr, char)
            pendingPeripherals.remove(addr)
            peripheral.setNotifyValue(true, char)
            delegate?.onDeviceConnected(addr)
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            val data = didUpdateValueForCharacteristic.value ?: return
            handleIncoming(data.toByteArray(), peripheral.identifier.UUIDString)
        }
    }

    // -----------------------------------------------------------------
    // Peripheral-manager (server) delegate
    // -----------------------------------------------------------------

    private inner class PeripheralManagerDelegate : NSObject(), CBPeripheralManagerDelegateProtocol {

        override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
            if (peripheral.state == CBManagerStatePoweredOn && active) startAdvertising()
        }

        override fun peripheralManager(peripheral: CBPeripheralManager, didReceiveWriteRequests: List<*>) {
            val requests = didReceiveWriteRequests.filterIsInstance<CBATTRequest>()
            requests.forEach { request ->
                val value = request.value
                if (value != null) {
                    handleIncoming(value.toByteArray(), request.central.identifier.UUIDString)
                }
            }
            requests.firstOrNull()?.let { peripheral.respondToRequest(it, CBATTErrorSuccess) }
        }

        @ObjCSignatureOverride
        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            central: CBCentral,
            didSubscribeToCharacteristic: CBCharacteristic,
        ) {
            val addr = central.identifier.UUIDString
            subscribedCentrals.put(addr, central)
            delegate?.onDeviceConnected(addr)
        }

        @ObjCSignatureOverride
        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            central: CBCentral,
            didUnsubscribeFromCharacteristic: CBCharacteristic,
        ) {
            val addr = central.identifier.UUIDString
            subscribedCentrals.remove(addr)
            addressPeerMap.remove(addr)
            frameAssemblers.remove(addr)
            delegate?.onDeviceDisconnected(addr)
        }
    }

    // -----------------------------------------------------------------
    // Debug / introspection
    // -----------------------------------------------------------------

    override fun isClientConnection(address: String): Boolean? = when {
        connectedPeripherals.get(address) != null -> true
        subscribedCentrals.get(address) != null -> false
        else -> null
    }

    // -----------------------------------------------------------------
    // BearerTransport debug / role surface — Android exposes per-role control and adapter
    // diagnostics; CoreBluetooth runs both roles together and hides the local address, so these
    // are no-ops/empty on Apple (they only back the Android debug sheet).
    // -----------------------------------------------------------------

    override fun setNicknameResolver(resolver: (String) -> String?) = Unit
    override fun setMeshServiceActive(active: Boolean) = Unit
    override fun startServer() = Unit
    override fun stopServer() = Unit
    override fun startClient() = Unit
    override fun stopClient() = Unit

    override fun getConnectedDeviceEntries(): List<Triple<String, Boolean, Int?>> =
        connectedPeripherals.keys.map { Triple(it, true, null) } +
            subscribedCentrals.keys.map { Triple(it, false, null) }

    override fun getLocalAdapterAddress(): String? = null
    override fun connectToAddress(address: String): Boolean = false
    override fun disconnectAddress(address: String) = Unit
    override fun getDebugInfo(): String =
        "CoreBluetooth: peripherals=${connectedPeripherals.size}, centrals=${subscribedCentrals.size}, active=$active"
}
