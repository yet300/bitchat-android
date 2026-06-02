package com.bitchat.android.core.domain.model

/**
 * Geo-channel levels (geohash precision). A clean port of the current geohash layer for
 * compatibility. The geocoder / coordinates stay in infrastructure — the domain only keeps
 * these values.
 */
enum class GeohashLevel(val precision: Int) {
    BUILDING(8),
    BLOCK(7),
    NEIGHBORHOOD(6),
    CITY(5),
    PROVINCE(4),
    REGION(2),
}

/** A computed geo-channel: level + geohash string. */
data class GeohashChannel(
    val level: GeohashLevel,
    val geohash: String,
)
