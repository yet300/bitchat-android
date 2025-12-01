package com.bitchat.android.nostr

import android.util.Log
import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * NostrSubscriptionManager
 * - Encapsulates subscription lifecycle with NostrRelayManager
 * - Injectable singleton service for use by multiple Stores
 */
@Singleton
class NostrSubscriptionManager @Inject constructor(
    private val nostrRelayManager: NostrRelayManager
) {
    companion object { private const val TAG = "NostrSubscriptionManager" }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun connect() = scope.launch { runCatching { nostrRelayManager.connect() }.onFailure { Log.e(TAG, "connect failed: ${it.message}") } }
    fun disconnect() = scope.launch { runCatching { nostrRelayManager.disconnect() }.onFailure { Log.e(TAG, "disconnect failed: ${it.message}") } }

    fun subscribeGiftWraps(pubkey: String, sinceMs: Long, id: String, handler: (NostrEvent) -> Unit) {
        scope.launch {
            val filter = NostrFilter.giftWrapsFor(pubkey, sinceMs)
            nostrRelayManager.subscribe(filter, id, handler)
        }
    }

    fun subscribeGeohash(geohash: String, sinceMs: Long, limit: Int, id: String, handler: (NostrEvent) -> Unit) {
        scope.launch {
            val filter = NostrFilter.geohashEphemeral(geohash, sinceMs, limit)
            nostrRelayManager.subscribeForGeohash(geohash, filter, id, handler, includeDefaults = false, nRelays = 5)
        }
    }

    fun unsubscribe(id: String) { scope.launch { runCatching { nostrRelayManager.unsubscribe(id) } } }
}
