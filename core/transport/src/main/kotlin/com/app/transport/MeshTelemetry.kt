package com.app.transport

import com.app.transport.debug.DebugScanResult
import com.app.transport.protocol.BitchatPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Telemetry port for the production mesh classes (BMS, bearers, PacketProcessor, GATT
 * managers): debug logging plus the debug toggles they honor. The debug-screen state
 * machinery ([com.app.transport.debug.DebugSettingsManager]) implements it; tests use
 * [NoOpMeshTelemetry]. Mesh constructors depend on this interface, not the manager (DIP).
 */
interface MeshTelemetry {

    // ------------------------------------------------------------------
    // Toggle reads honored by the mesh stack
    // ------------------------------------------------------------------

    val gattServerEnabled: StateFlow<Boolean>
    val gattClientEnabled: StateFlow<Boolean>
    val packetRelayEnabled: StateFlow<Boolean>

    val maxConnectionsOverall: StateFlow<Int>
    val maxServerConnections: StateFlow<Int>
    val maxClientConnections: StateFlow<Int>

    /** Injects a nickname resolver so logs show human names. */
    fun setNicknameResolver(resolver: (String) -> String?)

    // ------------------------------------------------------------------
    // Event logging
    // ------------------------------------------------------------------

    fun logIncoming(
        packet: BitchatPacket,
        fromPeerID: String,
        fromNickname: String?,
        fromDeviceAddress: String?,
        myPeerID: String,
    )

    fun logOutgoing(
        packetType: String,
        toPeerID: String?,
        toNickname: String?,
        toDeviceAddress: String?,
        previousHopPeerID: String? = null,
        packetVersion: UByte = 1u,
        routeInfo: String? = null,
    )

    fun logIncomingPacket(
        senderPeerID: String,
        senderNickname: String?,
        messageType: String,
        viaDeviceId: String?,
    )

    fun logPacketRelayDetailed(
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
        isRelay: Boolean = true,
        packetVersion: UByte = 1u,
        routeInfo: String? = null,
    )

    fun logPeerConnection(peerID: String, nickname: String, deviceID: String, isInbound: Boolean)

    fun logPeerDisconnection(peerID: String, nickname: String, deviceID: String)

    fun addScanResult(scanResult: DebugScanResult)
}

/** Silent implementation for unit tests: toggles default to enabled, logging is dropped. */
object NoOpMeshTelemetry : MeshTelemetry {
    private val enabled = MutableStateFlow(true)

    override val gattServerEnabled: StateFlow<Boolean> = enabled
    override val gattClientEnabled: StateFlow<Boolean> = enabled
    override val packetRelayEnabled: StateFlow<Boolean> = enabled

    private val defaultLimit = MutableStateFlow(8)
    override val maxConnectionsOverall: StateFlow<Int> = defaultLimit
    override val maxServerConnections: StateFlow<Int> = defaultLimit
    override val maxClientConnections: StateFlow<Int> = defaultLimit

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
    ) = Unit

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
    override fun addScanResult(scanResult: DebugScanResult) = Unit
}
