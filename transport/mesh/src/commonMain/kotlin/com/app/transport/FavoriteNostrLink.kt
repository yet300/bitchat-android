package com.app.transport

/**
 * Noise<->Nostr favorite mapping the mesh layer needs to route DMs (and favorite notifications) over
 * Nostr when a favorite peer is out of BLE range.
 *
 * The implementation (favorites persistence) lives in the app module; this port keeps mesh free of
 * that dependency and exposes only the narrow surface mesh uses (ISP) — note [isFavorite] instead of
 * leaking the app-side favorite-relationship type.
 */
interface FavoriteNostrLink {
    fun updatePeerFavoritedUs(noiseKey: ByteArray, theyFavoritedUs: Boolean)
    fun updateNostrPublicKey(noiseKey: ByteArray, nostrPubkey: String)
    fun updateNostrPublicKeyForPeerId(peerId: String, nostrPubkey: String)
    fun findNostrPubkey(noiseKey: ByteArray): String?
    fun isFavorite(noiseKey: ByteArray): Boolean
}
