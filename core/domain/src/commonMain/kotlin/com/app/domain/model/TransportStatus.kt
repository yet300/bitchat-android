package com.app.domain.model

/** A network transport bitMessage can run over. */
enum class TransportKind { BLUETOOTH, WIFI_AWARE, INTERNET, TOR }

/** Live state of a [TransportKind]. */
enum class TransportState {
    /** On and usable. */
    ON,

    /** Supported but switched off (radio disabled, or Tor turned off). */
    OFF,

    /** Not supported on this device / OS. */
    UNAVAILABLE,

    /** Supported but a runtime permission is missing. */
    PERMISSION_REQUIRED,
}

data class TransportStatus(
    val kind: TransportKind,
    val state: TransportState,
    val count: Int? = null,
)
