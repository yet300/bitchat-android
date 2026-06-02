package com.bitchat.android.core.domain.model

/**
 * Address of a conversation peer. In bitchat the same "address string" can mean three
 * different things, and the routing/resolution business logic constantly distinguishes
 * between them. [PeerId] makes that distinction explicit and centralizes the classification
 * in a single place (instead of regex parsing scattered across the codebase).
 *
 * Pure value object (KMP-ready, no Android/transport types).
 */
@JvmInline
value class PeerId(val raw: String) {

    enum class Kind {
        /** Ephemeral mesh peerID (16 hex) — lives only while a BLE session exists. */
        MESH_EPHEMERAL,

        /** Noise static public key (64 hex) — stable cross-session identity. */
        NOISE_STABLE,

        /** Nostr / geohash-DM alias (`nostr_<pub16>`). */
        NOSTR_ALIAS,
    }

    val kind: Kind
        get() = when {
            raw.startsWith(NOSTR_PREFIX) -> Kind.NOSTR_ALIAS
            raw.length == NOISE_HEX_LENGTH && raw.isHex() -> Kind.NOISE_STABLE
            else -> Kind.MESH_EPHEMERAL
        }

    companion object {
        const val NOSTR_PREFIX = "nostr_"
        const val MESH_HEX_LENGTH = 16
        const val NOISE_HEX_LENGTH = 64
    }
}

private fun String.isHex(): Boolean =
    isNotEmpty() && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
