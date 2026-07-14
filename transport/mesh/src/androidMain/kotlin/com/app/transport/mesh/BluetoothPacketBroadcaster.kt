package com.app.transport.mesh

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import com.app.common.AppDispatchers
import com.app.common.utils.Log
import com.app.transport.MeshConstants
import com.app.transport.MeshTrafficLog
import com.app.transport.model.RoutedPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Android radio adapter under the shared [BleSendCore] (C1 unification): supplies only the raw
 * GATT writes and the live neighbor snapshot. Fragmentation, target selection, source routing,
 * anti-loop, padding and relay telemetry all live in the commonMain core.
 *
 * In Bluetooth Low Energy (BLE):
 *
 * Peripheral (server):
 * Advertises, accepts connections, hosts a GATT server; remote devices subscribe to the
 * characteristic and we push frames via notify.
 *
 * Central (client):
 * Scans, initiates connections, hosts a GATT client; we push frames via characteristic write.
 */
internal class BluetoothPacketBroadcaster(
    private val connectionScope: CoroutineScope,
    private val connectionTracker: BluetoothConnectionTracker,
    fragmentManager: FragmentManager?,
    myPeerID: String,
    debugSettingsManager: MeshTrafficLog,
    transferProgressManager: TransferProgressManager,
    private val gattServerProvider: () -> BluetoothGattServer?,
    private val characteristicProvider: () -> BluetoothGattCharacteristic?,
    dispatchers: AppDispatchers = AppDispatchers(),
    private val config: BleRadioConfig = BleRadioConfig(),
) : BleRadioLink {

    companion object {
        private const val TAG = "BluetoothPacketBroadcaster"
        private const val CLEANUP_DELAY = MeshConstants.Mesh.BROADCAST_CLEANUP_DELAY_MS
    }

    private val broadcasterScope = CoroutineScope(dispatchers.io + SupervisorJob())

    // Per-link outbound back-pressure now lives in the shared [BleOutboundDispatcher]: it chunks a
    // frame to the link's usable MTU, keeps each frame's chunk run contiguous (replacing the old
    // per-address emissionLocks), and — instead of dropping a chunk when the stack reports busy —
    // stashes it and drains on the readiness signal (onNotificationSent / onCharacteristicWrite).
    private val outbound = BleOutboundDispatcher(
        scope = broadcasterScope,
        config = config,
        onOutboundDropped = { debugSettingsManager.onOutboundDropped(BearerId.BLE) },
    )

    // Client role only: Android permits a single outstanding write-without-response per GATT. A frame
    // is written whole (its bytes are pinned by BleSendPathGoldenTest); the next frame waits here
    // until onCharacteristicWrite frees the slot.
    private val outstandingWrites = ConcurrentHashMap.newKeySet<String>()

    private val sendCore = BleSendCore(
        scope = broadcasterScope,
        fragmentManager = fragmentManager,
        transferProgressManager = transferProgressManager,
        myPeerID = myPeerID,
        radio = this,
        trafficLog = debugSettingsManager,
        sourceRoutingEnabled = true,
        logTag = TAG,
        config = config,
    )

    fun setNicknameResolver(resolver: (String) -> String?) = sendCore.setNicknameResolver(resolver)

    fun broadcastPacket(routed: RoutedPacket) = sendCore.broadcastPacket(routed)

    fun sendToPeer(targetPeerID: String, routed: RoutedPacket): Boolean =
        sendCore.sendToPeer(targetPeerID, routed)

    fun cancelTransfer(transferId: String): Boolean = sendCore.cancelTransfer(transferId)

    fun flushDirectedSpool() = sendCore.flushDirectedSpool()

    // ------------------------------------------------------------------
    // BleRadioLink — the only genuinely Android part: GATT notify/write
    // ------------------------------------------------------------------

    override fun neighbors(): List<BleNeighbor> {
        val peers = connectionTracker.addressPeerMap
        val servers = connectionTracker.getSubscribedDevices().map { device ->
            BleNeighbor(device.address, isClient = false, peerID = peers[device.address])
        }
        val clients = connectionTracker.getConnectedDevices().values
            .filter { it.isClient && it.gatt != null && it.characteristic != null }
            .map { conn ->
                BleNeighbor(conn.device.address, isClient = true, peerID = peers[conn.device.address])
            }
        return servers + clients
    }

    override fun peerForAddress(linkAddress: String): String? =
        connectionTracker.addressPeerMap[linkAddress]

    override fun writeToNeighbor(neighbor: BleNeighbor, frame: ByteArray): Boolean {
        val addr = neighbor.linkAddress
        return if (neighbor.isClient) {
            if (connectionTracker.getDeviceConnection(addr) == null) return false
            // Client writes stay whole-frame (their bytes are golden-pinned): pass a max chunk large
            // enough that the frame is never split, and pace whole frames via the single-outstanding
            // slot. maxChunk = frame.size guarantees a single chunk.
            outbound.submit(
                linkAddress = addr,
                frame = frame,
                maxChunkBytes = frame.size.coerceAtLeast(1),
                writer = clientWriter(addr),
                priority = BleOutboundPriority.RELAY_HIGH,
                capBytes = config.clientLinkCapBytes,
            )
        } else {
            if (connectionTracker.getSubscribedDevices().none { it.address == addr }) return false
            outbound.submit(
                linkAddress = addr,
                frame = frame,
                maxChunkBytes = (connectionTracker.getNegotiatedMtu(addr) - 3).coerceAtLeast(1),
                writer = notifyWriter(addr),
                priority = BleOutboundPriority.RELAY_HIGH,
                capBytes = config.serverLinkCapBytes,
            )
        }
    }

    /** Server -> client push via characteristic notify (one MTU-sized chunk). */
    private fun notifyWriter(address: String): BleChunkWriter = BleChunkWriter { chunk ->
        val device = connectionTracker.getSubscribedDevices().firstOrNull { it.address == address }
            ?: return@BleChunkWriter ChunkWriteResult.GONE
        try {
            val characteristic = characteristicProvider() ?: return@BleChunkWriter ChunkWriteResult.GONE
            val server = gattServerProvider() ?: return@BleChunkWriter ChunkWriteResult.GONE
            characteristic.value = chunk
            // false = the stack's notify queue is full right now → BUSY; retry on onNotificationSent.
            if (server.notifyCharacteristicChanged(device, characteristic, false)) {
                ChunkWriteResult.SENT
            } else {
                ChunkWriteResult.BUSY
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error sending to server connection $address: ${e.message}")
            connectionScope.launch {
                delay(CLEANUP_DELAY)
                connectionTracker.removeSubscribedDevice(device)
                connectionTracker.addressPeerMap.remove(address)
            }
            ChunkWriteResult.GONE
        }
    }

    /** Client -> server push via characteristic write, single outstanding write per GATT. */
    private fun clientWriter(address: String): BleChunkWriter = BleChunkWriter { chunk ->
        if (address in outstandingWrites) return@BleChunkWriter ChunkWriteResult.BUSY
        val conn = connectionTracker.getDeviceConnection(address) ?: return@BleChunkWriter ChunkWriteResult.GONE
        try {
            val char = conn.characteristic ?: return@BleChunkWriter ChunkWriteResult.GONE
            val gatt = conn.gatt ?: return@BleChunkWriter ChunkWriteResult.GONE
            char.value = chunk
            if (gatt.writeCharacteristic(char)) {
                outstandingWrites.add(address)
                ChunkWriteResult.SENT
            } else {
                ChunkWriteResult.BUSY
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error sending to client connection $address: ${e.message}")
            connectionScope.launch {
                delay(CLEANUP_DELAY)
                connectionTracker.cleanupDeviceConnection(address)
            }
            ChunkWriteResult.GONE
        }
    }

    /** Server role: the stack finished a notification — drain the link's queued chunks. */
    fun onReady(address: String) = outbound.onReady(address)

    /** Client role: a write completed — free the single-outstanding slot and drain the next frame. */
    fun onWriteCompleted(address: String) {
        outstandingWrites.remove(address)
        outbound.onReady(address)
    }

    /** A link disconnected — drop its outbound buffer and any stale outstanding-write flag. */
    fun onLinkDropped(address: String) {
        outstandingWrites.remove(address)
        outbound.dropLink(address)
    }

    fun getDebugInfo(): String = buildString {
        appendLine("=== Packet Broadcaster Debug Info ===")
        append(sendCore.getDebugInfo())
        appendLine("Connection Scope Active: ${connectionScope.isActive}")
    }

    fun shutdown() {
        Log.d(TAG, "Shutting down BluetoothPacketBroadcaster")
        sendCore.shutdown()
        outbound.shutdown()
        broadcasterScope.cancel()
        Log.d(TAG, "BluetoothPacketBroadcaster shutdown complete")
    }
}
