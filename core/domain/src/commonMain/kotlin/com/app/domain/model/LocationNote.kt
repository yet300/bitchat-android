package com.app.domain.model

/**
 * A geo-anchored note (Nostr kind-1 text note tagged with a building-level geohash). The notes feed
 * is distinct from the geohash chat: longer-lived posts pinned to a place, not an ephemeral room.
 */
data class LocationNote(
    val id: String,
    val authorName: String,
    val content: String,
    val createdAtEpochSeconds: Int,
)
