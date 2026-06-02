package com.app.common.serialization

import kotlinx.serialization.json.Json

/**
 * Shared kotlinx.serialization [Json] configuration for the app.
 *
 * Tuned to mirror the previous Gson behaviour so existing persisted data and
 * cross-platform wire formats (Nostr) stay compatible:
 *  - [Json.configuration] omits nulls (Gson omitted nulls by default)
 *  - unknown keys are ignored (relays/peers may add fields)
 *  - lenient parsing tolerates minor formatting differences
 */
object JsonConfig {
    val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }
}
