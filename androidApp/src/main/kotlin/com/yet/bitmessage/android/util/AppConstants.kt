package com.yet.bitmessage.android.util

/**
 * Centralized application-wide constants.
 *
 * Mesh-domain constants (TTL, Mesh/Gatt, Fragmentation, Security, StoreForward, Power) were moved to
 * `com.app.transport.MeshConstants` ahead of the mesh extraction. The sections kept here belong to
 * layers that still live in `:app` (Nostr/Tor relay config, UI, Media, Services, Verification).
 */
object AppConstants {

    object UI {
        const val MAX_NICKNAME_LENGTH: Int = 15
        const val BASE_FONT_SIZE_SP: Int = 15
        const val MESSAGE_DEDUP_TIMEOUT_MS: Long = 30_000L
        const val SYSTEM_EVENT_DEDUP_TIMEOUT_MS: Long = 5_000L
        const val ACTIVE_PEERS_NOTIFICATION_INTERVAL_MS: Long = 300_000L
        const val ACTION_FORCE_FINISH: String = "com.yet.bitmessage.android.ACTION_FORCE_FINISH"
        const val PERMISSION_FORCE_FINISH: String = "com.yet.bitmessage.android.permission.FORCE_FINISH"
    }

    object Media {
        const val MAX_FILE_SIZE_BYTES: Long = 50L * 1024 * 1024
    }
}
