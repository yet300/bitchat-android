package com.app.transport

import com.app.transport.debug.DebugScanResult
import com.app.transport.mesh.BearerId
import com.app.transport.protocol.BitchatPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Debug toggles and connection limits the mesh stack honors (read-only flows).
 * Split from [MeshTrafficLog] per ISP: PacketRelayManager and the GATT server only
 * need this half.
 */
interface MeshDebugToggles {
    val gattServerEnabled: StateFlow<Boolean>
    val gattClientEnabled: StateFlow<Boolean>
    val packetRelayEnabled: StateFlow<Boolean>

    val maxConnectionsOverall: StateFlow<Int>
    val maxServerConnections: StateFlow<Int>
    val maxClientConnections: StateFlow<Int>
}

/**
 * Traffic/event logging sink for the mesh stack. BleBearer, the packet broadcaster and
 * PacketProcessor only need this half.
 */
interface MeshTrafficLog {

    /** Injects a nickname resolver so logs show human names. */
    fun setNicknameResolver(resolver: (String) -> String?)

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

    /**
     * A received frame was dropped at a bearer's bounded ingress buffer because the packet
     * pipeline could not drain it in time (e.g. a burst of Noise handshakes on entry to a
     * dense zone). Default no-op; the debug telemetry counts these per bearer so silent loss
     * of inbound frames — including NOISE_ENCRYPTED addressed to us — becomes observable.
     */
    fun onIncomingDropped(bearerId: BearerId) {}
}

/**
 * Full telemetry port: toggles + traffic log. The debug-screen state machinery
 * ([com.app.transport.debug.DebugSettingsManager]) implements it; tests use
 * [NoOpMeshTelemetry]. Mesh constructors depend on the narrowest interface they
 * actually use (DIP + ISP).
 */
interface MeshTelemetry : MeshDebugToggles, MeshTrafficLog

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
