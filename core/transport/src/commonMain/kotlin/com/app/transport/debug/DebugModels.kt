@file:OptIn(ExperimentalTime::class)

package com.app.transport.debug

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// MARK: - Data Models

/**
 * Different types of debug messages for categorization and formatting
 */
sealed class DebugMessage(val content: String, val timestamp: Instant = Clock.System.now()) {
    class SystemMessage(content: String) : DebugMessage("⚙️ $content")
    class PeerEvent(content: String) : DebugMessage(content)
    class PacketEvent(content: String) : DebugMessage(content)
    class RelayEvent(content: String) : DebugMessage(content)
}

/**
 * Scan result data for debugging
 */
data class DebugScanResult(
    val deviceName: String?,
    val deviceAddress: String,
    val rssi: Int,
    val peerID: String?,
    val timestamp: Instant = Clock.System.now()
)

/**
 * Connected device information for debugging
 */
data class ConnectedDevice(
    val deviceAddress: String,
    val peerID: String?,
    val nickname: String?,
    val rssi: Int?,
    val connectionType: ConnectionType,
    val isDirectConnection: Boolean
)

enum class ConnectionType {
    GATT_SERVER,
    GATT_CLIENT
}

/**
 * Packet relay statistics for monitoring network activity
 */
data class PacketRelayStats(
    val totalRelaysCount: Long = 0,
    val lastSecondRelays: Int = 0,
    val last10SecondRelays: Int = 0,
    val lastMinuteRelays: Int = 0,
    val last15MinuteRelays: Int = 0,
    val lastResetTime: Instant = Clock.System.now(),
    val lastSecondIncoming: Int = 0,
    val lastSecondOutgoing: Int = 0,
    val last10SecondIncoming: Int = 0,
    val last10SecondOutgoing: Int = 0,
    val lastMinuteIncoming: Int = 0,
    val lastMinuteOutgoing: Int = 0,
    val last15MinuteIncoming: Int = 0,
    val last15MinuteOutgoing: Int = 0,
    val totalIncomingCount: Long = 0,
    val totalOutgoingCount: Long = 0
)
