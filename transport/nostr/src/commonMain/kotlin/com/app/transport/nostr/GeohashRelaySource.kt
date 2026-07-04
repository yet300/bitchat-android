package com.app.transport.nostr

/**
 * Supplies the relay URLs closest to a geohash. commonMain seam so NostrRelayManager does not
 * depend on the androidMain RelayDirectory (which caches the relay CSV to a file and reads it via
 * an Android Application context). RelayDirectory implements this; an iOS source is a later follow-up.
 */
fun interface GeohashRelaySource {
    fun closestRelaysForGeohash(geohash: String, nRelays: Int): List<String>
}
