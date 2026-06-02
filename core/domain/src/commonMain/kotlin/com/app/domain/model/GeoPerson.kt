@file:OptIn(ExperimentalTime::class)

package com.app.domain.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * A participant in a geohash (location) channel. Identified by their Nostr pubkey (hex).
 * Nostr identity derivation / presence tracking is infrastructure; the domain keeps this view.
 */
data class GeoPerson(
    val pubkeyHex: String,
    val displayName: String,
    val isTeleported: Boolean = false,
    val lastSeen: Instant? = null,
)
