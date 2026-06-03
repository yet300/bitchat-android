package com.app.transport

/**
 * Supplies the current user's nickname for announcements and leave messages.
 *
 * Inverts the mesh layer's former dependency on the UI-side preferences: the
 * transport depends on this contract, while the implementation (backed by
 * persisted settings) lives in the app module.
 */
fun interface NicknameSource {

    /** Returns the saved nickname, or [fallback] (typically the peer id) if none. */
    fun nickname(fallback: String): String
}
