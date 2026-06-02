package com.app.domain.repository

import com.app.domain.model.Peer
import com.app.domain.model.PeerId
import kotlinx.coroutines.flow.Flow

/**
 * Access to mesh-network peer state.
 */
interface PeerRepository {

    /** Stream of peers (connectivity, session, fingerprint, rssi, etc.). */
    fun observePeers(): Flow<List<Peer>>

    /** Stream of overall connection state (is there at least one peer). */
    fun observeConnectionState(): Flow<Boolean>

    /** Current snapshot of peers (for one-shot resolutions). */
    suspend fun snapshot(): List<Peer>

    /** Find a peer by id. */
    suspend fun peer(id: PeerId): Peer?
}
