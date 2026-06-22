package com.app.crypto.noise

/**
 * Noise session limits and rekey thresholds (byte-for-byte matching the iOS client).
 *
 * Kept local to the crypto module; these previously lived in the app-wide
 * `AppConstants.Noise` and were only ever read by the Noise layer.
 */
internal object NoiseConstants {
    const val REKEY_TIME_LIMIT_MS: Long = 3_600_000L // 1 hour
    const val REKEY_MESSAGE_LIMIT_ENCRYPTION: Long = 1_000L // per session, encryption service policy
    const val REKEY_MESSAGE_LIMIT_SESSION: Long = 10_000L // session-level ceiling
    const val MAX_PAYLOAD_SIZE_BYTES: Int = 256
    const val HIGH_NONCE_WARNING_THRESHOLD: Long = 1_000_000_000L
}
