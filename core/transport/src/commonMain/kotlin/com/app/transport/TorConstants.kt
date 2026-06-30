package com.app.transport

/**
 * Tor (Arti) substrate configuration constants.
 *
 * Extracted from the monolith's god-config (`com.bitchat.android.util.AppConstants.Tor`); values are
 * copied verbatim (behavior-neutral).
 */
object TorConstants {
    const val DEFAULT_SOCKS_PORT: Int = 9060
    const val RESTART_DELAY_MS: Long = 2_000L
    const val INACTIVITY_TIMEOUT_MS: Long = 5_000L
    const val MAX_RETRY_ATTEMPTS: Int = 5
    const val STOP_TIMEOUT_MS: Long = 7_000L
}
