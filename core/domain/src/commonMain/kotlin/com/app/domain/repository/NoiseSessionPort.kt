package com.app.domain.repository

import com.app.domain.model.PeerId

/**
 * Narrow mesh Noise-session primitives needed to open an encrypted DM. The decision logic (who
 * initiates the handshake) is orchestration and lives in the domain use-case, not here; this port
 * only exposes the side-effecting mesh operations so it stays trivially implementable over BMS.
 */
interface NoiseSessionPort {

    /** True when an established Noise session already exists with [peerId]. */
    fun hasSession(peerId: PeerId): Boolean

    /** This device's current mesh peer id (16-hex), used for the initiator tie-break. */
    fun myPeerId(): String

    /** Start a Noise handshake with [peerId]. */
    fun initiateHandshake(peerId: PeerId)

    /** Send an identity announcement to [peerId] to prompt it to handshake. */
    fun announceTo(peerId: PeerId)
}
