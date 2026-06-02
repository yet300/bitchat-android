@file:OptIn(ExperimentalTime::class)

package com.bitchat.android.core.domain.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** State of the Noise session with a peer. */
enum class SessionState { NONE, HANDSHAKING, ESTABLISHED }

/**
 * A mesh-network peer. Keys are stored as hex strings (not ByteArray) so the domain stays a
 * clean value layer without binary types and hand-written equals/hashCode.
 */
data class Peer(
    val id: PeerId,
    val nickname: String,
    val isConnected: Boolean,
    val isDirect: Boolean,
    val session: SessionState = SessionState.NONE,
    val rssi: Int? = null,
    val fingerprint: Fingerprint? = null,
    val noiseKeyHex: String? = null,
    val isVerified: Boolean = false,
    val lastSeen: Instant? = null,
)
