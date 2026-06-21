package com.app.domain.repository

/** Resets the live mesh identity: clears in-memory session keys, re-derives a fresh peerID,
 *  and restarts advertising/scanning. Called after panic wipe so old keys do not survive. */
interface MeshResetPort {
    suspend fun reset()
}
