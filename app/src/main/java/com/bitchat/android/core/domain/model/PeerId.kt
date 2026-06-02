package com.bitchat.android.core.domain.model

/**
 * Адрес собеседника. В bitchat одна и та же «строка-адрес» может означать три разные вещи,
 * и бизнес-логика маршрутизации/резолва постоянно их различает. Тип [PeerId] делает это
 * различие явным и собирает классификацию в одном месте (вместо regex по всему коду).
 *
 * Чистый value-объект (KMP-ready, без Android/транспортных типов).
 */
@JvmInline
value class PeerId(val raw: String) {

    enum class Kind {
        /** Эфемерный mesh peerID (16 hex) — живёт только пока есть BLE-сессия. */
        MESH_EPHEMERAL,

        /** Noise static public key (64 hex) — стабильная межсессионная личность. */
        NOISE_STABLE,

        /** Nostr/гео-DM алиас (`nostr_<pub16>`). */
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
