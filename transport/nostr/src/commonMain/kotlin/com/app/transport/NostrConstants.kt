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

    // ±20% jitter on each backoff so relays that dropped together don't reconnect in lockstep
    // (iOS TransportConfig.nostrRelayBackoffJitterRatio).
    const val BACKOFF_JITTER_RATIO: Double = 0.2

    // DNS failures are transient on mobile (airplane mode / lift / tunnel), not a dead host: retry
    // them with a large floor instead of giving up. Divergence from iOS, which marks DNS permanent.
    const val DNS_MIN_BACKOFF_MS: Long = 60_000L

    // Once a relay exhausts MAX_RECONNECT_ATTEMPTS it is "exhausted", but the failure decays: after
    // this cooldown the background re-probe gives it a fresh chance (iOS nostrRelayFailureCooldownSeconds).
    const val FAILURE_COOLDOWN_MS: Long = 600_000L

    // How often the background loop re-probes exhausted-but-cooled-down relays. iOS has no dedicated
    // timer (it piggybacks on foreground/activity); the transport layer can't see those, so we poll.
    const val RELAY_REPROBE_INTERVAL_MS: Long = 120_000L

    // How often to retry events that were requeued because a connected relay's send buffer was full
    // (the "connected but busy" trySend hole). Short, since the buffer usually clears in moments.
    const val PENDING_FLUSH_INTERVAL_MS: Long = 3_000L

    // Transport
    const val READ_ACK_INTERVAL_MS: Long = 350L

    // Deduplicator
    const val DEFAULT_DEDUP_CAPACITY: Int = 10_000

    // Relay subscription validation
    const val SUBSCRIPTION_VALIDATION_INTERVAL_MS: Long = 30_000L
}
