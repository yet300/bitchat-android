package com.app.domain.repository

import com.app.domain.model.TransportKind
import com.app.domain.model.TransportStatus
import kotlinx.coroutines.flow.Flow

/**
 * Live per-transport connectivity state and user-initiated repair. The implementation is
 * platform-specific (radio adapters, runtime permissions, system settings); the domain only
 * sees [TransportStatus] and a single "enable" intent — the always-available re-enable path
 * (Nostr-only is a valid runtime; mesh is additive).
 */
interface ConnectivityRepository {

    fun observe(): Flow<List<TransportStatus>>

    /**
     * Repair a transport: request the missing permission / open the relevant system settings /
     * toggle Tor — whatever moves [kind] toward [TransportState.ON][com.app.domain.model.TransportState.ON].
     */
    suspend fun enable(kind: TransportKind)
}
