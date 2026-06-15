package com.yet.bitmessage.feature.chats.main

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.panels.ChildPanels
import com.arkivanov.decompose.router.panels.ChildPanelsMode
import com.arkivanov.decompose.router.panels.Panels
import com.arkivanov.decompose.router.panels.PanelsNavigation
import com.arkivanov.decompose.router.panels.childPanels
import com.arkivanov.decompose.router.panels.navigate
import com.arkivanov.decompose.router.panels.pop
import com.arkivanov.decompose.router.panels.setMode
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.router.slot.SlotNavigation
import com.arkivanov.decompose.router.slot.activate
import com.arkivanov.decompose.router.slot.childSlot
import com.arkivanov.decompose.router.slot.dismiss
import com.arkivanov.decompose.value.Value
import com.yet.bitmessage.feature.chats.conversations.ConversationsComponent
import com.yet.bitmessage.feature.chats.conversations.connectivity.ConnectivityComponent
import com.yet.bitmessage.feature.chats.details.ChatComponent
import com.yet.bitmessage.feature.chats.details.ChatConfig
import dev.zacsweers.metro.Inject
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer

internal class DefaultChatsComponent(
    componentContext: ComponentContext,
    private val conversationsFactory: ConversationsComponent.Factory,
    private val chatFactory: ChatComponent.Factory,
    private val connectivityFactory: ConnectivityComponent.Factory,
) : ChatsComponent, ComponentContext by componentContext {

    private val navigation = PanelsNavigation<Unit, ChatConfig, Nothing>()
    private val sheetNavigation = SlotNavigation<SheetConfig>()

    override val panels: Value<ChildPanels<*, ChatsComponent.Main, *, ChatsComponent.Details, Nothing, Nothing>> =
        childPanels(
            source = navigation,
            initialPanels = { Panels(main = Unit) },
            serializers = Unit.serializer() to ChatConfig.serializer(),
            handleBackButton = true,
            mainFactory = { _, ctx ->
                ChatsComponent.Main.Conversations(
                    conversationsFactory.create(
                        componentContext = ctx,
                        onConversationSelected = { id ->
                            navigation.navigate { it.copy(details = ChatConfig.from(id)) }
                        },
                        onConnectivityRequested = { sheetNavigation.activate(SheetConfig.Connectivity) },
                    ),
                )
            },
            detailsFactory = { config, ctx ->
                ChatsComponent.Details.Chat(
                    chatFactory.create(
                        componentContext = ctx,
                        config = config,
                        onFinished = { navigation.navigate { it.copy(details = null) } },
                    ),
                )
            },
        )

    override val sheetSlot: Value<ChildSlot<*, ChatsComponent.SheetChild>> =
        childSlot(
            source = sheetNavigation,
            serializer = SheetConfig.serializer(),
            handleBackButton = true,
            childFactory = { config, ctx ->
                when (config) {
                    SheetConfig.Connectivity ->
                        ChatsComponent.SheetChild.Connectivity(connectivityFactory.create(ctx))
                }
            },
        )

    override fun setMode(mode: ChildPanelsMode) = navigation.setMode(mode)

    override fun onBackClicked() = navigation.pop()

    override fun onDismissSheet() = sheetNavigation.dismiss()

    @Serializable
    private sealed interface SheetConfig {
        @Serializable
        data object Connectivity : SheetConfig
    }
}

@Inject
internal class DefaultChatsComponentFactory(
    private val conversationsFactory: ConversationsComponent.Factory,
    private val chatFactory: ChatComponent.Factory,
    private val connectivityFactory: ConnectivityComponent.Factory,
) : ChatsComponent.Factory {
    override fun create(componentContext: ComponentContext): ChatsComponent =
        DefaultChatsComponent(
            componentContext = componentContext,
            conversationsFactory = conversationsFactory,
            chatFactory = chatFactory,
            connectivityFactory = connectivityFactory,
        )
}
