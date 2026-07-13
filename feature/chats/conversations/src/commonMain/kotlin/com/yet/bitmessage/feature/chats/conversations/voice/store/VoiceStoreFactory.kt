package com.yet.bitmessage.feature.chats.conversations.voice.store

import com.app.domain.repository.VoiceRepository
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.launch

internal class VoiceStoreFactory(
    private val storeFactory: StoreFactory,
    private val voiceRepository: VoiceRepository,
) {
    fun create(): VoiceStore =
        object : VoiceStore,
            Store<VoiceStore.Intent, VoiceStore.State, VoiceStore.Label> by storeFactory.create(
                name = "VoiceStore",
                initialState = VoiceStore.State(),
                bootstrapper = SimpleBootstrapper(VoiceStore.Action.Subscribe),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl,
            ) {}

    private object ReducerImpl : Reducer<VoiceStore.State, VoiceStore.Msg> {
        override fun VoiceStore.State.reduce(msg: VoiceStore.Msg): VoiceStore.State =
            when (msg) {
                is VoiceStore.Msg.Received -> copy(received = (received + msg.burst).takeLast(MAX_LOG))
            }
    }

    private inner class ExecutorImpl :
        CoroutineExecutor<VoiceStore.Intent, VoiceStore.Action, VoiceStore.State, VoiceStore.Msg, VoiceStore.Label>() {

        override fun executeAction(action: VoiceStore.Action) {
            when (action) {
                VoiceStore.Action.Subscribe -> scope.launch {
                    voiceRepository.incomingBursts.collect { burst ->
                        dispatch(VoiceStore.Msg.Received(VoiceStore.ReceivedBurst(burst.peerId, burst.durationMs)))
                        publish(VoiceStore.Label.Play(burst.frames))
                    }
                }
            }
        }

        override fun executeIntent(intent: VoiceStore.Intent) {
            when (intent) {
                is VoiceStore.Intent.Send -> scope.launch {
                    voiceRepository.broadcast(intent.frames, intent.durationMs)
                }
            }
        }
    }

    private companion object {
        const val MAX_LOG = 50
    }
}
