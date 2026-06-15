package com.app.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Tor routing preference (on/off). Nostr-only is always a valid runtime, so this is purely the
 * user's opt-in; the transport layer reads the same preference.
 */
interface TorRepository {

    fun observeTorEnabled(): Flow<Boolean>

    suspend fun setTorEnabled(enabled: Boolean)
}
