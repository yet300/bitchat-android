package com.app.transport.sync

internal object SyncDefaults {
    // Default values used when debug prefs are unavailable
    const val DEFAULT_FILTER_BYTES: Int = 256
    const val DEFAULT_FPR_PERCENT: Double = 1.0

    // Receiver-side hard cap to avoid DoS (also enforced in RequestSyncPacket)
    const val MAX_ACCEPT_FILTER_BYTES: Int = 1024

    // TTL for neighbor-only sync packets (sync floods to direct neighbors only)
    val SYNC_TTL_HOPS: UByte = 0u

    // Periodic cleanup interval for the gossip sync caches
    const val CLEANUP_INTERVAL_MS: Long = 60_000L

    // Safety ceiling for the announce store (latest announce per peer). Announces are
    // bounded by the 180s liveness prune, NOT by seenCapacity (iOS has no cap at all);
    // this fixed ceiling only guards against hostile peer-ID spoofing. 2000 covers the
    // 1000-peer design target with 2x headroom at ~0.6 MB worst case (~300 B/announce).
    const val DEFAULT_ANNOUNCE_CAPACITY: Int = 2000
}

