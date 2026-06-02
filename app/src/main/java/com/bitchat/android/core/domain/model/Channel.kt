package com.bitchat.android.core.domain.model

/**
 * Канал `#name`. Крипто канала (PBKDF2/AES-GCM) — инфраструктура; в domain только мета.
 */
data class Channel(
    val tag: String,
    val isJoined: Boolean = false,
    val isCreator: Boolean = false,
    val isPasswordProtected: Boolean = false,
    val memberCount: Int = 0,
) {
    companion object {
        /** Нормализует имя канала к виду `#name`. */
        fun tag(raw: String): String = if (raw.startsWith("#")) raw else "#$raw"
    }
}
