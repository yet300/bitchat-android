package com.app.transport.mesh

/**
 * commonMain parity copy of the reference iOS `TransportConfig` BLE knobs (values verified against
 * `bitchat/bitchat-ios/bitchat/Services/TransportConfig.swift`). Kept as one place both platforms
 * read so the Android radio and the CoreBluetooth radio behave identically where the policy is
 * platform-independent. Platform-only quantities (MTU negotiation, `maximumUpdateValueLength`) are
 * NOT here — the platform still resolves those.
 */
data class BleRadioConfig(
    // --- outbound back-pressure (per-link chunk buffer / dispatcher) ---
    /** Retry cadence for a busy link when the readiness signal is missed (iOS bleNotificationRetryDelayMs). */
    val notifyRetryDelayMs: Long = 25L,
    /** Per server (notify) link buffer cap: iOS caps 128 frames; ~128 * 512B MTU chunks. */
    val serverLinkCapBytes: Int = 128 * 512,
    /** Per client (write) link buffer cap (iOS blePendingWriteBufferCapBytes = 1 MB). */
    val clientLinkCapBytes: Int = 1 * 1024 * 1024,

    // --- frame-level send queue (BleSendCore) ---
    /** Bound for the shared broadcast frame queue (was Channel.UNLIMITED). */
    val sendQueueCapacity: Int = 512,

    // --- fragment / transfer pacing (FragmentingPacketSender) ---
    /** Inter-fragment spacing (iOS bleFragmentSpacingMs). */
    val fragmentSpacingMs: Long = 30L,
    /** Simultaneous large media transfers (iOS bleMaxConcurrentTransfers). */
    val maxConcurrentTransfers: Int = 2,

    // --- connection scheduling (iOS-only today; Android has PowerManager) ---
    val maxCentralLinks: Int = 6,
    val connectRateLimitMs: Long = 500L,
    val connectionCandidatesMax: Int = 100,
    val connectTimeoutBackoffWindowMs: Long = 120_000L,
    val timeoutDiscoveryIgnoreMs: Long = 15_000L,
    val disconnectDiscoveryIgnoreMs: Long = 3_000L,
    val weakLinkCooldownMs: Long = 30_000L,
    val weakLinkRssiCutoff: Int = -90,

    // --- RSSI gates (dBm) ---
    val rssiThresholdDefault: Int = -90,
    val rssiIsolatedBase: Int = -95,
    val rssiIsolatedRelaxed: Int = -100,
    val rssiConnectedThreshold: Int = -85,
    val isolationRelaxThresholdMs: Long = 30_000L,

    // --- scan duty policy ---
    val highDegreeThreshold: Int = 6,
    val dutyOnMs: Long = 5_000L,
    val dutyOffMs: Long = 10_000L,
    val dutyOnDenseMs: Long = 3_000L,
    val dutyOffDenseMs: Long = 15_000L,

    // --- redundant central-role link retirement (iOS bleLinkRebindCooldownSeconds) ---
    /** At most one redundant-link retirement pass per peer per this window. */
    val linkRebindCooldownMs: Long = 60_000L,

    // --- directed spool when no writable links (iOS bleDirectedSpoolWindowSeconds) ---
    val directedSpoolWindowMs: Long = 60_000L,
)
