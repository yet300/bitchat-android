package com.bitchat.android.util

/**
 * Centralized application-wide constants.
 *
 * Mesh-domain constants (TTL, Mesh/Gatt, Fragmentation, Security, StoreForward, Power) were moved to
 * `com.app.transport.MeshConstants` ahead of the mesh extraction. The sections kept here belong to
 * layers that still live in `:app` (Nostr/Tor relay config, UI, Media, Services, Verification).
 */
object AppConstants {

    object Nostr {
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

    object Tor {
        const val DEFAULT_SOCKS_PORT: Int = 9060
        const val RESTART_DELAY_MS: Long = 2_000L
        const val INACTIVITY_TIMEOUT_MS: Long = 5_000L
        const val MAX_RETRY_ATTEMPTS: Int = 5
        const val STOP_TIMEOUT_MS: Long = 7_000L
    }

    object UI {
        const val MAX_NICKNAME_LENGTH: Int = 15
        const val BASE_FONT_SIZE_SP: Int = 15
        const val MESSAGE_DEDUP_TIMEOUT_MS: Long = 30_000L
        const val SYSTEM_EVENT_DEDUP_TIMEOUT_MS: Long = 5_000L
        const val ACTIVE_PEERS_NOTIFICATION_INTERVAL_MS: Long = 300_000L
        const val ACTION_FORCE_FINISH: String = "com.bitchat.android.ACTION_FORCE_FINISH"
        const val PERMISSION_FORCE_FINISH: String = "com.bitchat.android.permission.FORCE_FINISH"
    }

    object Media {
        const val MAX_FILE_SIZE_BYTES: Long = 50L * 1024 * 1024
    }
}
