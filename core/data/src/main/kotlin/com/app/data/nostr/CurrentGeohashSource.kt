package com.app.data.nostr

/**
 * Supplies the geohash of the currently selected Location channel (or null when not in one).
 *
 * Lets the data-layer [NostrTransport] derive a fallback source geohash for geohash DMs without
 * depending on the app's `LocationChannelManager` (which holds UI-selected state). The app wires an
 * implementation onto [NostrTransport.currentGeohashSource].
 */
fun interface CurrentGeohashSource {
    fun currentGeohash(): String?
}
