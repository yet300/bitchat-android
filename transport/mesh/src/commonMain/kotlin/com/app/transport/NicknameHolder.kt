package com.app.transport

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [NicknameSource] owned by the transport SDK. The data layer pushes the persisted
 * nickname (encrypted at rest) into this holder; the mesh reads it synchronously when building
 * announces, so the transport never touches a settings backend and the nickname never needs a
 * plaintext copy.
 */
class NicknameHolder : NicknameSource {

    private val current = MutableStateFlow<String?>(null)

    fun set(nickname: String) {
        current.value = nickname
    }

    /** Returns the pushed nickname, or [fallback] when nothing non-blank was ever pushed. */
    override fun nickname(fallback: String): String =
        current.value?.takeIf { it.isNotBlank() } ?: fallback
}
