package com.yet.bitmessage.feature.chats.conversations.channels

import com.app.common.decompose.asValue
import com.app.common.decompose.coroutineScope
import com.app.domain.model.RetentionPolicy
import com.app.domain.repository.ChannelRepository
import com.app.domain.repository.MessageRepository
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.router.slot.SlotNavigation
import com.arkivanov.decompose.router.slot.activate
import com.arkivanov.decompose.router.slot.childSlot
import com.arkivanov.decompose.router.slot.dismiss
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.yet.bitmessage.feature.chats.conversations.channels.store.ChannelsStore
import com.yet.bitmessage.feature.chats.conversations.channels.store.ChannelsStoreFactory
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.launch

internal class DefaultChannelsComponent(
    componentContext: ComponentContext,
    storeFactory: ChannelsStoreFactory,
    private val onChannelSelected: (String) -> Unit,
    private val onClose: () -> Unit,
) : ChannelsComponent, ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }
    private val dialogNav = SlotNavigation<ChannelDialog>()

    override val model: Value<ChannelsComponent.Model> = store.asValue().map { state ->
        ChannelsComponent.Model(
            isLoading = state.isLoading,
            channels = state.channels,
            error = state.error,
        )
    }

    override val dialog: Value<ChildSlot<*, ChannelDialog>> =
        childSlot(
            source = dialogNav,
            // Transient UI overlays — not worth persisting across process death.
            serializer = null,
            handleBackButton = true,
            childFactory = { config, _ -> config },
        )

    init {
        // Store-originated dialog opens (protected join, retention current policy) arrive as labels.
        coroutineScope().launch {
            store.labels.collect { label ->
                when (label) {
                    is ChannelsStore.Label.NeedsPassword ->
                        dialogNav.activate(ChannelDialog.Password(label.tag, ChannelDialog.Password.Mode.JOIN))
                    is ChannelsStore.Label.ShowRetention ->
                        dialogNav.activate(ChannelDialog.Retention(label.tag, label.current))
                }
            }
        }
    }

    override fun onChannelClicked(tag: String) = onChannelSelected(tag)

    override fun onJoin(tag: String) = store.accept(ChannelsStore.Intent.Join(tag))

    override fun onSetPasswordClicked(tag: String) =
        dialogNav.activate(ChannelDialog.Password(tag, ChannelDialog.Password.Mode.SET))

    override fun onRetentionClicked(tag: String) = store.accept(ChannelsStore.Intent.OpenRetention(tag))

    override fun onLeave(tag: String) = store.accept(ChannelsStore.Intent.Leave(tag))

    override fun onSubmitPassword(tag: String, mode: ChannelDialog.Password.Mode, password: String) {
        when (mode) {
            ChannelDialog.Password.Mode.SET -> store.accept(ChannelsStore.Intent.SetPassword(tag, password))
            ChannelDialog.Password.Mode.JOIN -> store.accept(ChannelsStore.Intent.Join(tag, password))
        }
        dialogNav.dismiss()
    }

    override fun onRetentionSelected(tag: String, policy: RetentionPolicy) {
        store.accept(ChannelsStore.Intent.SetRetention(tag, policy))
        dialogNav.dismiss()
    }

    override fun onDismissDialog() = dialogNav.dismiss()

    override fun onCloseClicked() = onClose()
}

@Inject
internal class DefaultChannelsComponentFactory(
    private val storeFactory: StoreFactory,
    private val channelRepository: ChannelRepository,
    private val messageRepository: MessageRepository,
) : ChannelsComponent.Factory {
    override fun create(
        componentContext: ComponentContext,
        onChannelSelected: (String) -> Unit,
        onClose: () -> Unit,
    ): ChannelsComponent = DefaultChannelsComponent(
        componentContext = componentContext,
        storeFactory = ChannelsStoreFactory(storeFactory, channelRepository, messageRepository),
        onChannelSelected = onChannelSelected,
        onClose = onClose,
    )
}
