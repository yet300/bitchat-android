package com.app.domain.app

import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide foreground/background signal.
 *
 * Lets data-layer components throttle high-volume background work — e.g. pausing the Nostr geohash
 * presence firehose (kind 20001) while backgrounded to cut mobile data, without dropping low-volume
 * chat-message delivery.
 */
interface AppForegroundState {
    /** `true` while the app process is in the foreground (started), `false` once fully backgrounded. */
    val isForeground: StateFlow<Boolean>
}
