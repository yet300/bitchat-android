package com.app.transport.nostr

import com.app.common.utils.Log

/**
 * Initializer for LocationNotesManager with all dependencies
 * Extracts initialization logic from MainActivity for better separation of concerns
 */
object LocationNotesInitializer {

    private const val TAG = "LocationNotesInitializer"

    /**
     * Initialize LocationNotesManager with all required dependencies
     *
     * @return true if initialization succeeded, false otherwise
     */
    fun initialize(relayDirectory: GeohashRelaySource, relayManager: NostrRelayManager, locationNotesManager: LocationNotesManager, nostrIdentityBridge: NostrIdentityBridge): Boolean {
        return try {
            locationNotesManager.initialize(
                relayManager = { relayManager },
                subscribe = { filter, id, handler ->
                    // CRITICAL FIX: Extract geohash properly from filter using getGeohash() method
                    val geohashFromFilter = filter.getGeohash() ?: run {
                        Log.e(TAG, "❌ Cannot extract geohash from filter for location notes")
                        return@initialize id // Return subscription ID even on error
                    }

                    Log.d(TAG, "📍 Location Notes subscribing to geohash: $geohashFromFilter")

                    relayManager.subscribeForGeohash(
                        geohash = geohashFromFilter,
                        filter = filter,
                        relayDirectory = relayDirectory,
                        id = id,
                        handler = handler,
                        includeDefaults = true,
                        nRelays = 5
                    )
                },
                unsubscribe = { id ->
                    relayManager.unsubscribe(id)
                },
                sendEvent = { event, relayUrls ->
                    if (relayUrls != null) {
                        relayManager.sendEvent(event, relayUrls)
                    } else {
                        relayManager.sendEvent(event)
                    }
                },
                deriveIdentity = { geohash ->
                    nostrIdentityBridge.deriveIdentity(geohash)
                }
            )
            Log.d(TAG, "✅ Location Notes Manager initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize Location Notes Manager: ${e.message}", e)
            false
        }
    }
}
