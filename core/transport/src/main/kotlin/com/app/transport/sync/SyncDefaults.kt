package com.app.transport.sync

object SyncDefaults {
    // Default values used when debug prefs are unavailable
    const val DEFAULT_FILTER_BYTES: Int = 256
    const val DEFAULT_FPR_PERCENT: Double = 1.0

    // Receiver-side hard cap to avoid DoS (also enforced in RequestSyncPacket)
    const val MAX_ACCEPT_FILTER_BYTES: Int = 1024

    // TTL for neighbor-only sync packets (sync floods to direct neighbors only)
    val SYNC_TTL_HOPS: UByte = 0u

    // Periodic cleanup interval for the gossip sync caches
    const val CLEANUP_INTERVAL_MS: Long = 60_000L
}

