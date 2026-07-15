@file:OptIn(ExperimentalUuidApi::class)

package com.app.transport

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Mesh-domain configuration constants (commonMain).
 *
 * Extracted from the monolith's god-config (`com.bitchat.android.util.AppConstants`) into the
 * transport module ahead of the `mesh/` extraction. Values are copied verbatim — this move is
 * behavior-neutral. iOS byte-compatibility: the packet TTL and the GATT UUIDs must stay identical.
 * The BLE GATT UUIDs ([Mesh.Gatt]) are typed kotlin.uuid.Uuid (the project-wide idiom); platform
 * code adapts each to its native UUID type at the call site (java.util.UUID on Android via
 * Uuid.toJavaUuid(); CBUUID on native via CBUUID.UUIDWithString(uuid.toString())).
 */
object MeshConstants {

    // Packet time-to-live (hops)
    val MESSAGE_TTL_HOPS: UByte = 7u     // Default TTL for regular packets

    object Mesh {
        // Peer lifecycle
        const val STALE_PEER_TIMEOUT_MS: Long = 180_000L // 3 minutes
        const val PEER_CLEANUP_INTERVAL_MS: Long = 60_000L

        // BLE connection tracking
        const val CONNECTION_RETRY_DELAY_MS: Long = 5_000L
        const val MAX_CONNECTION_ATTEMPTS: Int = 3
        const val CONNECTION_CLEANUP_DELAY_MS: Long = 500L
        const val CONNECTION_CLEANUP_INTERVAL_MS: Long = 30_000L
        const val BROADCAST_CLEANUP_DELAY_MS: Long = 500L

        // GATT client RSSI updates
        const val RSSI_UPDATE_INTERVAL_MS: Long = 5_000L

        // Default ATT MTU before any exchange (BLE spec); usable value size is MTU − 3.
        const val DEFAULT_ATT_MTU: Int = 23

        object Gatt {
            val SERVICE_UUID: Uuid = Uuid.parse("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
            val CHARACTERISTIC_UUID: Uuid = Uuid.parse("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")
            val DESCRIPTOR_UUID: Uuid = Uuid.parse("00002902-0000-1000-8000-00805f9b34fb")
        }
    }

    object Fragmentation {
        const val FRAGMENT_SIZE_THRESHOLD: Int = 512
        const val MAX_FRAGMENT_SIZE: Int = 469
        const val FRAGMENT_TIMEOUT_MS: Long = 30_000L
        const val CLEANUP_INTERVAL_MS: Long = 10_000L
        // 1 MiB payload / 469-byte fragments ≈ 2236; headroom for TLV + packet framing.
        // (Previously 256 ≈ 116 KiB — broke native↔KMP 1 MiB file interop.)
        const val MAX_FRAGMENTS_PER_ID: Int = 4096
        const val MAX_FRAGMENT_TOTAL_BYTES: Int = 1_048_576
        const val MAX_ACTIVE_FRAGMENT_SETS: Int = 64
        const val MAX_GLOBAL_FRAGMENT_TOTAL_BYTES: Long = 4L * 1_048_576L
    }

    object Security {
        const val MESSAGE_TIMEOUT_MS: Long = 300_000L
        const val CLEANUP_INTERVAL_MS: Long = 300_000L
        const val MAX_PROCESSED_MESSAGES: Int = 10_000
        const val MAX_PROCESSED_KEY_EXCHANGES: Int = 1_000
    }

    object StoreForward {
        const val MESSAGE_CACHE_TIMEOUT_MS: Long = 43_200_000L // 12h
        const val MAX_CACHED_MESSAGES: Int = 100
        const val MAX_CACHED_MESSAGES_FAVORITES: Int = 1_000
        const val CLEANUP_INTERVAL_MS: Long = 600_000L
    }

    object Power {
        const val CRITICAL_BATTERY_PERCENT: Int = 10
        const val LOW_BATTERY_PERCENT: Int = 20
        const val MEDIUM_BATTERY_PERCENT: Int = 50
        const val SCAN_ON_DURATION_NORMAL_MS: Long = 8_000L
        const val SCAN_OFF_DURATION_NORMAL_MS: Long = 2_000L
        const val SCAN_ON_DURATION_POWER_SAVE_MS: Long = 2_000L
        const val SCAN_OFF_DURATION_POWER_SAVE_MS: Long = 28_000L
        const val SCAN_ON_DURATION_ULTRA_LOW_MS: Long = 1_000L
        const val SCAN_OFF_DURATION_ULTRA_LOW_MS: Long = 29_000L
        const val MAX_CONNECTIONS_NORMAL: Int = 8
        const val MAX_CONNECTIONS_POWER_SAVE: Int = 8
        const val MAX_CONNECTIONS_ULTRA_LOW: Int = 4
    }
}
