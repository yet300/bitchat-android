package com.app.transport

/**
 * Nostr relay configuration constants.
 *
 * Extracted from the monolith's god-config (`com.bitchat.android.util.AppConstants.Nostr`) into the
 * transport module ahead of the nostr-network extraction. Values are copied verbatim
 * (behavior-neutral).
 */
object NostrConstants {
    // Relay backoff
    const val INITIAL_BACKOFF_INTERVAL_MS: Long = 1_000L
    const val MAX_BACKOFF_INTERVAL_MS: Long = 300_000L
    const val BACKOFF_MULTIPLIER: Double = 2.0
    const val MAX_RECONNECT_ATTEMPTS: Int = 10

    // Transport
    const val READ_ACK_INTERVAL_MS: Long = 350L

    // Deduplicator
    const val DEFAULT_DEDUP_CAPACITY: Int = 10_000

    // Relay subscription validation
    const val SUBSCRIPTION_VALIDATION_INTERVAL_MS: Long = 30_000L
}
