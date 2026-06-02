package com.app.domain.model

/**
 * A channel `#name`. Channel crypto (PBKDF2/AES-GCM) is infrastructure; the domain keeps only metadata.
 */
data class Channel(
    val tag: String,
    val isJoined: Boolean = false,
    val isCreator: Boolean = false,
    val isPasswordProtected: Boolean = false,
    val memberCount: Int = 0,
) {
    companion object {
        /** Normalizes a channel name to the `#name` form. */
        fun tag(raw: String): String = if (raw.startsWith("#")) raw else "#$raw"
    }
}
