package com.yet.bitmessage.feature.chats.conversations.channels.store

import com.app.domain.model.ConversationId
import com.app.domain.repository.ChannelRepository
import com.app.domain.repository.JoinResult
import com.app.domain.repository.MessageRepository
import com.app.domain.usecase.ApplyRetentionUseCase
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class ChannelsStoreFactory(
    private val storeFactory: StoreFactory,
    private val channelRepository: ChannelRepository,
    messageRepository: MessageRepository,
) {
    private val applyRetention = ApplyRetentionUseCase(messageRepository)

    fun create(): ChannelsStore =
        object : ChannelsStore,
            Store<ChannelsStore.Intent, ChannelsStore.State, ChannelsStore.Label> by storeFactory.create(
                name = "ChannelsStore",
                initialState = ChannelsStore.State(),
                bootstrapper = SimpleBootstrapper(ChannelsStore.Action.Subscribe),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl,
            ) {}

    private object ReducerImpl : Reducer<ChannelsStore.State, ChannelsStore.Msg> {
        override fun ChannelsStore.State.reduce(msg: ChannelsStore.Msg): ChannelsStore.State =
            when (msg) {
                is ChannelsStore.Msg.Loaded -> copy(isLoading = false, channels = msg.channels)
                is ChannelsStore.Msg.Error -> copy(error = msg.message)
            }
    }

    private inner class ExecutorImpl :
        CoroutineExecutor<ChannelsStore.Intent, ChannelsStore.Action, ChannelsStore.State, ChannelsStore.Msg, ChannelsStore.Label>() {

        override fun executeAction(action: ChannelsStore.Action) {
            when (action) {
                ChannelsStore.Action.Subscribe -> scope.launch {
                    channelRepository.observeChannels().collect { dispatch(ChannelsStore.Msg.Loaded(it)) }
                }
            }
        }

        override fun executeIntent(intent: ChannelsStore.Intent) {
            when (intent) {
                is ChannelsStore.Intent.Join -> scope.launch {
                    when (val result = channelRepository.join(intent.tag, intent.password)) {
                        JoinResult.Joined -> Unit
                        JoinResult.NeedsPassword -> publish(ChannelsStore.Label.NeedsPassword(intent.tag))
                        is JoinResult.Failed -> dispatch(ChannelsStore.Msg.Error(result.reason))
                    }
                }
                is ChannelsStore.Intent.Leave -> scope.launch { channelRepository.leave(intent.tag) }
                is ChannelsStore.Intent.SetPassword -> scope.launch {
                    channelRepository.setPassword(intent.tag, intent.password)
                }
                is ChannelsStore.Intent.OpenRetention -> scope.launch {
                    val current = channelRepository.observeRetention(intent.tag).first()
                    publish(ChannelsStore.Label.ShowRetention(intent.tag, current))
                }
                is ChannelsStore.Intent.SetRetention -> scope.launch {
                    channelRepository.setRetention(intent.tag, intent.policy)
                    applyRetention(ConversationId.Channel(intent.tag), intent.policy)
                }
            }
        }
    }
}
