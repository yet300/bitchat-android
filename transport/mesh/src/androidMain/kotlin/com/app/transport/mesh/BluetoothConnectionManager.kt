package com.app.transport.mesh

import android.bluetooth.*
import android.content.Context
import com.app.common.utils.Log
import com.app.transport.MeshTelemetry
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/**
 * Power-optimized Bluetooth connection manager with comprehensive memory management
 * Integrates with PowerManager for adaptive power consumption
 * Coordinates smaller, focused components for better maintainability
 */
internal class BluetoothConnectionManager(
    private val context: Context, 
    private val myPeerID: String,
    private val debugSettingsManager: MeshTelemetry,
    private val fragmentManager: FragmentManager? = null,
    private val transferProgressManager: TransferProgressManager,
) : PowerManagerDelegate, BearerTransport {

    companion object {
        private const val TAG = "BluetoothConnectionManager"
    }
    
    // Core Bluetooth components
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    
    // Power management
    private val powerManager = PowerManager(context.applicationContext)
    
    // Coroutines
    private val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Component managers
    private val permissionManager = BluetoothPermissionManager(context)
    private val connectionTracker = BluetoothConnectionTracker(connectionScope, powerManager)
    private val packetBroadcaster: BluetoothPacketBroadcaster = BluetoothPacketBroadcaster(
        connectionScope, connectionTracker, fragmentManager, myPeerID, debugSettingsManager,
        transferProgressManager,
        gattServerProvider = { serverManager.getGattServer() },
        characteristicProvider = { serverManager.getCharacteristic() },
    )
    
    // Delegate for component managers to call back to main manager
    private val componentDelegate = object : BluetoothConnectionManagerDelegate {
        override fun onPacketReceived(packet: BitchatPacket, peerID: String, device: BluetoothDevice?) {
            Log.d(TAG, "onPacketReceived: Packet received from ${device?.address} ($peerID)")
            device?.let { bluetoothDevice ->
                // Get current RSSI for this device and update if available
                val currentRSSI = connectionTracker.getBestRSSI(bluetoothDevice.address)
                if (currentRSSI != null) {
                    delegate?.onRSSIUpdated(bluetoothDevice.address, currentRSSI)
                }
            }

            if (peerID == myPeerID) return // Ignore messages from self

            delegate?.onPacketReceived(packet, peerID, device?.address)
        }

        override fun onDeviceConnected(device: BluetoothDevice) {
            // Trigger limit enforcement immediately upon any new connection
            enforceStrictLimits()
            delegate?.onDeviceConnected(device.address)
        }

        override fun onDeviceDisconnected(device: BluetoothDevice) {
            // Drop the link's outbound buffer so it neither retries onto a dead link nor leaks.
            packetBroadcaster.onLinkDropped(device.address)
            delegate?.onDeviceDisconnected(device.address)
        }

        override fun onRSSIUpdated(deviceAddress: String, rssi: Int) {
            delegate?.onRSSIUpdated(deviceAddress, rssi)
        }

        override fun onNotificationSent(deviceAddress: String) {
            packetBroadcaster.onReady(deviceAddress)
        }

        override fun onCharacteristicWriteCompleted(deviceAddress: String) {
            packetBroadcaster.onWriteCompleted(deviceAddress)
        }
    }
    
    private val serverManager = BluetoothGattServerManager(
        context, connectionScope, connectionTracker, permissionManager, powerManager, componentDelegate, myPeerID, debugSettingsManager
    )
    private val clientManager = BluetoothGattClientManager(
        context, connectionScope, connectionTracker, permissionManager, powerManager, componentDelegate, debugSettingsManager
    )
    
    // Service state
    private var isActive = false
    
    // External delegate (the shared BleBearer) — platform-free, address-based.
    override var delegate: BearerTransportDelegate? = null

    // Public property for address-peer mapping
    override val addressPeerMap get() = connectionTracker.addressPeerMap

    init {
        powerManager.delegate = this
        // Observe debug settings to enforce role state while active
        try {
            val dbg = debugSettingsManager
            // Role enable/disable
            connectionScope.launch {
                dbg.gattServerEnabled.collect { enabled ->
                    if (!isActive) return@collect
                    if (enabled) startServer() else stopServer()
                }
            }
            connectionScope.launch {
                dbg.gattClientEnabled.collect { enabled ->
                    if (!isActive) return@collect
                    if (enabled) startClient() else stopClient()
                }
            }
            
            // Centralized limit enforcement on any setting change
            connectionScope.launch {
                combine(
                    dbg.maxConnectionsOverall,
                    dbg.maxServerConnections,
                    dbg.maxClientConnections
                ) { _, _, _ -> 
                    // We don't need the values here, we just need to trigger enforcement
                    Unit 
                }.collect {
                    if (isActive) {
                        enforceStrictLimits()
                    }
                }
            }
        } catch (_: Exception) { }
    }
    
    /**
     * Centralized connection limit enforcement
     */
    private fun enforceStrictLimits() {
        if (!isActive) return
        
        try {
            val dbg = debugSettingsManager
            val maxOverall = dbg.maxConnectionsOverall.value
            val maxServer = dbg.maxServerConnections.value
            val maxClient = dbg.maxClientConnections.value
            
            // Get list of connections to evict to satisfy all constraints
            val toEvict = connectionTracker.getConnectionsToEvict(maxOverall, maxServer, maxClient)
            
            if (toEvict.isNotEmpty()) {
                Log.i(TAG, "Enforcing limits (max: $maxOverall, s: $maxServer, c: $maxClient) - evicting ${toEvict.size} connections")
                
                toEvict.forEach { conn ->
                    if (conn.isClient) {
                        Log.d(TAG, "Evicting client ${conn.device.address}")
                        try { conn.gatt?.disconnect() } catch (_: Exception) { }
                    } else {
                        Log.d(TAG, "Evicting server ${conn.device.address}")
                        serverManager.disconnectDevice(conn.device)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enforcing limits: ${e.message}")
        }
    }
    
    /**
     * Start all Bluetooth services with power optimization
     */
    override fun startServices(): Boolean {
        Log.i(TAG, "Starting power-optimized Bluetooth services...")
        
        if (!permissionManager.hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return false
        }
        
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled")
            return false
        }
        
        try {
            isActive = true
            Log.d(TAG, "ConnectionManager activated (permissions and adapter OK)")

        // set the adapter's name to our 8-character peerID for iOS privacy, TODO: Make this configurable
        // try {
        //     if (bluetoothAdapter?.name != myPeerID) {
        //         bluetoothAdapter?.name = myPeerID
        //         Log.d(TAG, "Set Bluetooth adapter name to peerID: $myPeerID for iOS compatibility.")
        //     }
        // } catch (se: SecurityException) {
        //     Log.e(TAG, "Missing BLUETOOTH_CONNECT permission to set adapter name.", se)
        // }

            // Start all component managers
            connectionScope.launch {
                // Start connection tracker first
                connectionTracker.start()
                
                // Start power manager
                powerManager.start()
                
                // Start server/client based on debug settings
                val dbg = debugSettingsManager
                val startServer = dbg.gattServerEnabled.value != false
                val startClient = dbg.gattClientEnabled.value != false

                if (startServer) {
                    if (!serverManager.start()) {
                        Log.e(TAG, "Failed to start server manager")
                        this@BluetoothConnectionManager.isActive = false
                        return@launch
                    }
                    Log.d(TAG, "GATT Server started")
                } else {
                    Log.i(TAG, "GATT Server disabled by debug settings; not starting")
                }

                if (startClient) {
                    if (!clientManager.start()) {
                        Log.e(TAG, "Failed to start client manager")
                        this@BluetoothConnectionManager.isActive = false
                        return@launch
                    }
                    Log.d(TAG, "GATT Client started")
                } else {
                    Log.i(TAG, "GATT Client disabled by debug settings; not starting")
                }
                
                Log.i(TAG, "Bluetooth services started successfully")
            }
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Bluetooth services: ${e.message}")
            isActive = false
            return false
        }
    }
    
    /**
     * Stop all Bluetooth services with proper cleanup
     */
    override fun stopServices() {
        Log.i(TAG, "Stopping power-optimized Bluetooth services")
        
        isActive = false
        
        connectionScope.launch {
            Log.d(TAG, "Stopping client/server and power components...")
            // Stop component managers
            clientManager.stop()
            serverManager.stop()
            
            // Stop power manager
            powerManager.stop()
            
            // Stop connection tracker
            connectionTracker.stop()
            
            // Cancel the coroutine scope
            connectionScope.cancel()
            
            Log.i(TAG, "All Bluetooth services stopped")
        }
    }

    /**
     * Indicates whether this instance can be safely reused for a future start.
     * Returns false if its coroutine scope has been cancelled.
     */
    fun isReusable(): Boolean {
        val active = connectionScope.isActive
        if (!active) {
            Log.d(TAG, "BluetoothConnectionManager isReusable=false (scope cancelled)")
        }
        return active
    }
    
    /**
     * Broadcast packet to connected devices with connection limit enforcement
     * Automatically fragments large packets to fit within BLE MTU limits
     */
    override fun broadcastPacket(routed: RoutedPacket) {
        if (!isActive) return
        
        packetBroadcaster.broadcastPacket(routed)
    }

    override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean {
        if (!isActive) return false
        return packetBroadcaster.sendToPeer(peerID, routed)
    }

    override fun cancelTransfer(transferId: String): Boolean {
        return packetBroadcaster.cancelTransfer(transferId)
    }

    

    // Expose role controls for debug UI
    override fun startServer() { connectionScope.launch { serverManager.start() } }
    override fun stopServer() { connectionScope.launch { serverManager.stop() } }
    override fun startClient() { connectionScope.launch { clientManager.start() } }
    override fun stopClient() { connectionScope.launch { clientManager.stop() } }

    // Inject nickname resolver for broadcaster logs
    override fun setNicknameResolver(resolver: (String) -> String?) { packetBroadcaster.setNicknameResolver(resolver) }

    /** Forward the mesh-foreground-service signal to the power manager (see [PowerManager.setMeshServiceActive]). */
    override fun setMeshServiceActive(active: Boolean) { powerManager.setMeshServiceActive(active) }
    override fun setAppIsActive(active: Boolean) = Unit

    // Debug snapshots for connected devices
    override fun getConnectedDeviceEntries(): List<Triple<String, Boolean, Int?>> {
        return try {
            connectionTracker.getConnectedDevices().values.map { dc ->
                val rssi = if (dc.rssi != Int.MIN_VALUE) dc.rssi else null
                Triple(dc.device.address, dc.isClient, rssi)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Expose local adapter address for debug UI
    override fun getLocalAdapterAddress(): String? = try { bluetoothAdapter?.address } catch (e: Exception) { null }

    override fun isClientConnection(address: String): Boolean? {
        return try { connectionTracker.getConnectedDevices()[address]?.isClient } catch (e: Exception) { null }
    }

    /**
     * Public: connect/disconnect helpers for debug UI
     */
    override fun connectToAddress(address: String): Boolean = clientManager.connectToAddress(address)
    override fun disconnectAddress(address: String) { connectionTracker.disconnectDevice(address) }


    // Optionally disconnect all connections (server and client)
    fun disconnectAll() {
        connectionScope.launch {
            // Stop and restart to force disconnects
            clientManager.stop()
            serverManager.stop()
            delay(200)
            if (isActive) {
                // Restart managers if service is active
                serverManager.start()
                clientManager.start()
            }
        }
    }


    /**
     * Get connected device count
     */
    fun getConnectedDeviceCount(): Int = connectionTracker.getConnectedDeviceCount()
    
    /**
     * Get debug information including power management
     */
    override fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Bluetooth Connection Manager ===")
            appendLine("Bluetooth MAC Address: ${bluetoothAdapter?.address}")
            appendLine("Active: $isActive")
            appendLine("Bluetooth Enabled: ${bluetoothAdapter?.isEnabled}")
            appendLine("Has Permissions: ${permissionManager.hasBluetoothPermissions()}")
            appendLine("GATT Server Active: ${serverManager.getGattServer() != null}")
            appendLine()
            appendLine(powerManager.getPowerInfo())
            appendLine()
            appendLine(connectionTracker.getDebugInfo())
        }
    }
    
    // MARK: - PowerManagerDelegate Implementation
    
    override fun onPowerModeChanged(newMode: PowerManager.PowerMode) {
        Log.i(TAG, "Power mode changed to: $newMode")
        
        connectionScope.launch {
            // Avoid rapid scan restarts by checking if we need to change scan behavior
            val wasUsingDutyCycle = powerManager.shouldUseDutyCycle()
            
            // Update advertising with new power settings if server enabled
            val serverEnabled = try { debugSettingsManager.gattServerEnabled.value } catch (_: Exception) { true }
            if (serverEnabled) {
                serverManager.restartAdvertising()
            } else {
                serverManager.stop()
            }
            
            // Only restart scanning if the duty cycle behavior changed
            val nowUsingDutyCycle = powerManager.shouldUseDutyCycle()
            if (wasUsingDutyCycle != nowUsingDutyCycle) {
                Log.d(TAG, "Duty cycle behavior changed (${wasUsingDutyCycle} -> ${nowUsingDutyCycle}), restarting scan")
                val clientEnabled = try { debugSettingsManager.gattClientEnabled.value } catch (_: Exception) { true }
                if (clientEnabled) {
                    clientManager.restartScanning()
                } else {
                    clientManager.stop()
                }
            } else {
                Log.d(TAG, "Duty cycle behavior unchanged, keeping existing scan state")
            }
            
            // Enforce connection limits
            enforceStrictLimits()
        }
    }
    
    override fun onScanStateChanged(shouldScan: Boolean) {
        clientManager.onScanStateChanged(shouldScan)
    }
    
    // MARK: - Private Implementation - All moved to component managers
}

/**
 * Delegate interface for Bluetooth connection manager callbacks
 */
internal interface BluetoothConnectionManagerDelegate {
    fun onPacketReceived(packet: BitchatPacket, peerID: String, device: BluetoothDevice?)
    fun onDeviceConnected(device: BluetoothDevice)
    fun onDeviceDisconnected(device: BluetoothDevice)
    fun onRSSIUpdated(deviceAddress: String, rssi: Int)

    /** Server role: the stack finished sending a queued notification — drain the next chunk. */
    fun onNotificationSent(deviceAddress: String) {}

    /** Client role: a characteristic write completed — the single outstanding-write slot is free. */
    fun onCharacteristicWriteCompleted(deviceAddress: String) {}
}
