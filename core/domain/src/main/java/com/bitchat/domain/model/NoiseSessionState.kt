package com.bitchat.domain.model

/**
 * Session states matching iOS implementation
 */
sealed class NoiseSessionState {
    object Uninitialized : NoiseSessionState()
    object Handshaking : NoiseSessionState()
    object Established : NoiseSessionState()
    data class Failed(val error: Throwable) : NoiseSessionState()

    override fun toString(): String = when (this) {
        is Uninitialized -> "uninitialized"
        is Handshaking -> "handshaking"
        is Established -> "established"
        is Failed -> "failed: ${error.message}"
    }
}