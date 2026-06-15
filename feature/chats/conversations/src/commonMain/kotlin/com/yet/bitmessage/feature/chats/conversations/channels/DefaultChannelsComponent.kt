package com.yet.bitmessage.feature.chats.conversations.channels

import com.app.common.decompose.asValue
import com.app.domain.repository.ChannelRepository
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yet.bitmessage.feature.chats.conversations.channels.store.ChannelsStore
import com.yet.bitmessage.feature.chats.conversations.channels.store.ChannelsStoreFactory
import dev.zacsweers.metro.Inject

internal class DefaultChannelsComponent(
    componentContext: ComponentContext,
    storeFactory: ChannelsStoreFactory,
    private val onChannelSelected: (String) -> Unit,
    private val onClose: () -> Unit,
) : ChannelsComponent, ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }

    override val model: Value<ChannelsComponent.Model> = store.asValue().map { state ->
        ChannelsComponent.Model(
            isLoading = state.isLoading,
            channels = state.channels,
            passwordPromptFor = state.passwordPromptFor,
            error = state.error,
        )
    }

    override fun onChannelClicked(tag: String) = onChannelSelected(tag)

    override fun onJoin(tag: String) = store.accept(ChannelsStore.Intent.Join(tag))

    override fun onSubmitPassword(tag: String, password: String) =
        store.accept(ChannelsStore.Intent.Join(tag, password))

    override fun onDismissPasswordPrompt() = store.accept(ChannelsStore.Intent.DismissPasswordPrompt)

    override fun onLeave(tag: String) = store.accept(ChannelsStore.Intent.Leave(tag))

    override fun onSetPassword(tag: String, password: String) =
        store.accept(ChannelsStore.Intent.SetPassword(tag, password))

    override fun onCloseClicked() = onClose()
}

@Inject
internal class DefaultChannelsComponentFactory(
    private val storeFactory: StoreFactory,
    private val channelRepository: ChannelRepository,
) : ChannelsComponent.Factory {
    override fun create(
        componentContext: ComponentContext,
        onChannelSelected: (String) -> Unit,
        onClose: () -> Unit,
    ): ChannelsComponent = DefaultChannelsComponent(
        componentContext = componentContext,
        storeFactory = ChannelsStoreFactory(storeFactory, channelRepository),
        onChannelSelected = onChannelSelected,
        onClose = onClose,
    )
}
