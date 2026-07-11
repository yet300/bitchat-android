package com.app.domain.model

/** A live geohash bulletin-board post. */
data class BoardPost(
    /** Lowercase-hex of the 16-byte post ID. */
    val idHex: String,
    /** Empty = the mesh-local board. */
    val geohash: String,
    val content: String,
    /** Lowercase-hex of the author's 32-byte Ed25519 key. */
    val authorKeyHex: String,
    val authorNickname: String,
    val createdAt: Long,
    val expiresAt: Long,
    val isUrgent: Boolean,
    /** Whether this device authored the post. */
    val isMine: Boolean,
)
