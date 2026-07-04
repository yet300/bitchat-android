package com.app.transport.mesh

import android.bluetooth.BluetoothDevice
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
) : BleRadioLink {

    companion object {
        private const val TAG = "BluetoothPacketBroadcaster"
        private const val CLEANUP_DELAY = MeshConstants.Mesh.BROADCAST_CLEANUP_DELAY_MS
    }

    private val broadcasterScope = CoroutineScope(dispatchers.io + SupervisorJob())

    private val sendCore = BleSendCore(
        scope = broadcasterScope,
        fragmentManager = fragmentManager,
        transferProgressManager = transferProgressManager,
        myPeerID = myPeerID,
        radio = this,
        trafficLog = debugSettingsManager,
        sourceRoutingEnabled = true,
        logTag = TAG,
    )

    fun setNicknameResolver(resolver: (String) -> String?) = sendCore.setNicknameResolver(resolver)

    fun broadcastPacket(routed: RoutedPacket) = sendCore.broadcastPacket(routed)

    fun sendToPeer(targetPeerID: String, routed: RoutedPacket): Boolean =
        sendCore.sendToPeer(targetPeerID, routed)

    fun cancelTransfer(transferId: String): Boolean = sendCore.cancelTransfer(transferId)

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
        return if (neighbor.isClient) {
            val conn = connectionTracker.getDeviceConnection(neighbor.linkAddress) ?: return false
            writeToDeviceConn(conn, frame)
        } else {
            val device = connectionTracker.getSubscribedDevices()
                .firstOrNull { it.address == neighbor.linkAddress } ?: return false
            notifyDevice(device, frame)
        }
    }

    /** Server -> client push via characteristic notify. */
    private fun notifyDevice(device: BluetoothDevice, data: ByteArray): Boolean {
        return try {
            val characteristic = characteristicProvider() ?: return false
            characteristic.value = data
            gattServerProvider()?.notifyCharacteristicChanged(device, characteristic, false) ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Error sending to server connection ${device.address}: ${e.message}")
            connectionScope.launch {
                delay(CLEANUP_DELAY)
                connectionTracker.removeSubscribedDevice(device)
                connectionTracker.addressPeerMap.remove(device.address)
            }
            false
        }
    }

    /** Client -> server push via characteristic write. */
    private fun writeToDeviceConn(
        deviceConn: BluetoothConnectionTracker.DeviceConnection,
        data: ByteArray,
    ): Boolean {
        return try {
            deviceConn.characteristic?.let { char ->
                char.value = data
                deviceConn.gatt?.writeCharacteristic(char) ?: false
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Error sending to client connection ${deviceConn.device.address}: ${e.message}")
            connectionScope.launch {
                delay(CLEANUP_DELAY)
                connectionTracker.cleanupDeviceConnection(deviceConn.device.address)
            }
            false
        }
    }

    fun getDebugInfo(): String = buildString {
        appendLine("=== Packet Broadcaster Debug Info ===")
        append(sendCore.getDebugInfo())
        appendLine("Connection Scope Active: ${connectionScope.isActive}")
    }

    fun shutdown() {
        Log.d(TAG, "Shutting down BluetoothPacketBroadcaster")
        sendCore.shutdown()
        broadcasterScope.cancel()
        Log.d(TAG, "BluetoothPacketBroadcaster shutdown complete")
    }
}
