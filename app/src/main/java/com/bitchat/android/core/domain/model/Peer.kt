@file:OptIn(ExperimentalTime::class)

package com.bitchat.android.core.domain.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Состояние Noise-сессии с пиром. */
enum class SessionState { NONE, HANDSHAKING, ESTABLISHED }

/**
 * Пир mesh-сети. Ключи храним как hex-строки (а не ByteArray), чтобы domain оставался
 * чистым value-слоем без бинарных типов и ручных equals/hashCode.
 */
data class Peer(
    val id: PeerId,
    val nickname: String,
    val isConnected: Boolean,
    val isDirect: Boolean,
    val session: SessionState = SessionState.NONE,
    val rssi: Int? = null,
    val fingerprint: Fingerprint? = null,
    val noiseKeyHex: String? = null,
    val isVerified: Boolean = false,
    val lastSeen: Instant? = null,
)
