package com.yet.bitmessage.feature.chats.conversations.voice.store

import com.arkivanov.mvikotlin.core.store.Store

internal interface VoiceStore :
    Store<VoiceStore.Intent, VoiceStore.State, VoiceStore.Label> {

    data class State(
        val received: List<ReceivedBurst> = emptyList(),
    )

    /** A short summary of one inbound burst, for the on-screen activity log. */
    data class ReceivedBurst(val peerId: String, val durationMs: Int)

    sealed interface Intent {
        /** Broadcast one captured push-to-talk burst (already-encoded AAC-LC frames). */
        data class Send(val frames: List<ByteArray>, val durationMs: Int) : Intent
    }

    sealed interface Action {
        data object Subscribe : Action
    }

    sealed interface Msg {
        data class Received(val burst: ReceivedBurst) : Msg
    }

    /** One-shot: hand an inbound burst's frames to the platform player (owned by the UI). */
    sealed interface Label {
        data class Play(val frames: List<ByteArray>) : Label
    }
}
