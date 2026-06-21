package com.app.data.nostr

/**
 * Supplies the geohash of the currently selected Location channel (or null when not in one).
 *
 * Lets the data-layer [NostrMessageSender] derive a fallback source geohash for geohash DMs without
 * depending on the app's `LocationChannelManager` (which holds UI-selected state). The
 * `GeohashRepository` singleton implements it and is bound into the graph (DataBindings).
 */
fun interface CurrentGeohashSource {
    fun currentGeohash(): String?
}
