package com.app.transport.model

/**
 * Maps a MIME type to the bitchat message category. Pure mapping shared across the
 * mesh receive path and the Nostr DM ingest; kept in commonMain so both platforms agree.
 */
fun messageTypeForMime(mime: String): BitchatMessageType {
    val lower = mime.lowercase()
    return when {
        lower.startsWith("image/") -> BitchatMessageType.Image
        lower.startsWith("audio/") -> BitchatMessageType.Audio
        else -> BitchatMessageType.File
    }
}
